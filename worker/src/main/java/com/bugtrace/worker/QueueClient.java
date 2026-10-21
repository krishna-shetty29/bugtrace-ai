package com.bugtrace.worker;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import com.bugtrace.worker.WorkerController.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="worker.queue.enabled",havingValue="true")
public class QueueClient {
    private static final Logger log=LoggerFactory.getLogger(QueueClient.class);
    private final WorkerService engine;
    private final String backend,workerId,snapshotId,expectedHash;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper=JsonMapper.builder().build();
    private Map<String,Object> active;
    private long lastHeartbeat,lastWarning;
    private boolean submitted;
    private boolean accessRejected;
    private CompletionOutbox outbox;
    private CompletionOutbox.Pending pendingCompletion;
    private boolean outboxLoaded;
    private java.util.function.LongSupplier clock=System::nanoTime;
    private RetryBackoff queueRetry=new RetryBackoff(clock),presenceRetry=new RetryBackoff(clock);
    private long leaseDeadline;
    private String accessToken="";

    public QueueClient(WorkerService engine,@Value("${worker.queue.backend-url}") String backend,
        @Value("${worker.queue.worker-id}") String workerId,@Value("${worker.snapshot-id}") String snapshotId,
        @Value("${worker.content-hash}") String expectedHash) {
        this.engine=engine; this.backend=BackendOrigin.normalize(backend); this.workerId=workerId;
        this.snapshotId=snapshotId; this.expectedHash=expectedHash;
        if (!workerId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid worker ID");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QueueClient(WorkerService engine,@Value("${worker.queue.backend-url}") String backend,
        @Value("${worker.queue.worker-id}") String workerId,@Value("${worker.snapshot-id}") String snapshotId,
        @Value("${worker.content-hash}") String expectedHash,@Value("${WORKER_ACCESS_TOKEN:}") String accessToken) {
        this(engine,backend,workerId,snapshotId,expectedHash);
        if(accessToken==null||!accessToken.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Set a snapshot-bound WORKER_ACCESS_TOKEN issued by the project owner");
        this.accessToken=accessToken;
        outbox=new CompletionOutbox(engine.queueStateRoot(),this.backend,workerId,snapshotId);
    }

    QueueClient(WorkerService engine,String backend,String worker,String snapshot,String hash,String key,java.util.function.LongSupplier clock){
        this(engine,backend,worker,snapshot,hash,key);this.clock=clock;queueRetry=new RetryBackoff(clock);presenceRetry=new RetryBackoff(clock);
    }
    /** Independent idle/busy presence; execution heartbeat remains separate. */
    @Scheduled(fixedDelay=15000)
    public synchronized void reportPresence() {
        if(accessRejected||!presenceRetry.ready())return;
        try {
            String readiness="BASE_VERIFIED";
            try {engine.verifyRegisteredBaseBeforeClaim();}catch(Exception invalid){readiness="NOT_READY";}
            Map<String,Object> contact=new LinkedHashMap<>();
            contact.put("workerId",workerId);contact.put("snapshotId",snapshotId);contact.put("contentHash",expectedHash);contact.put("readiness",readiness);
            Object execution=null;
            if(active!=null&&submitted){
                var result=engine.result(UUID.fromString(active.get("executionId").toString()));
                if(Set.of("QUEUED","PREPARING","RUNNING","CANCEL_REQUESTED").contains(result.state()))execution=active.get("executionId");
            }
            contact.put("currentExecutionId",execution);
            Response response=post("/api/workers/presence",contact);
            if(Set.of(401,403).contains(response.status)) {
                stopForRejectedAccess();
                log.warn("Worker presence authorization rejected; restart with a valid matching worker credential");
                return;
            }
            requireSuccess(response);presenceRetry.success();
        }catch(Exception unavailable){presenceRetry.failed();log.warn("Worker presence unavailable; will retry with bounded backoff. No lease renewed.");}
    }

    @Scheduled(fixedDelay=1000)
    public synchronized void poll() {
        if(accessRejected)return;
        try {
            if(!outboxLoaded){pendingCompletion=outbox.load();outboxLoaded=true;if(pendingCompletion!=null)active=pendingCompletion.claim();}
            if(active!=null&&submitted&&pendingCompletion==null&&clock.getAsLong()>=leaseDeadline&&Set.of("QUEUED","PREPARING","RUNNING","CANCEL_REQUESTED").contains(engine.result(UUID.fromString(active.get("executionId").toString())).state())){abandon(UUID.fromString(active.get("executionId").toString()),UUID.fromString(snapshotId));return;}
            if(!queueRetry.ready())return;
            if(pendingCompletion!=null){uploadPending();return;}
            if (active==null) {
                UUID.fromString(snapshotId); // Fail closed if operator registration is missing.
                engine.verifyRegisteredBaseBeforeClaim();
                Response response=post("/api/queue/claim",Map.of("workerId",workerId,"snapshotId",snapshotId));
                if (response.status==204){queueRetry.success();return;}
                if(rejected(response)){return;}
                requireSuccess(response);queueRetry.success();
                active=mapper.readValue(response.body,Map.class);
                submitted=false; lastHeartbeat=0;leaseDeadline=clock.getAsLong()+((Number)active.getOrDefault("leaseSeconds",45)).longValue()*1_000_000_000L;
                log.info("Claimed queued execution {} attempt {}",active.get("jobId"),active.get("attempt"));
            }
            UUID execution=UUID.fromString(active.get("executionId").toString());
            UUID snapshot=UUID.fromString(active.get("snapshotId").toString());
            if (!submitted) {
                try {
                    engine.validateQueueClaim(active);
                    engine.submit(new JobRequest(execution,snapshot,Operation.valueOf(active.get("operation").toString()),
                        (String)active.get("testSelector"),((Number)active.get("timeoutSeconds")).intValue(),
                        active.get("generatedTest")==null?null:mapper.convertValue(active.get("generatedTest"),GeneratedTestOverlay.Payload.class),
                        active.get("notAfter")==null?null:java.time.Instant.parse(active.get("notAfter").toString())));
                    submitted=true;
                } catch (Exception exception) {
                    // Report an explicit setup failure instead of silently dropping the claim.
                    finish(synthetic("Worker could not start attempt: "+exception.getMessage()),"","");
                    return;
                }
            }
            var result=engine.result(execution);
            if(List.of("QUEUED","PREPARING","RUNNING","CANCEL_REQUESTED").contains(result.state())){
            if (System.currentTimeMillis()-lastHeartbeat>=5000) {
                Map<String,Object> progress=new LinkedHashMap<>();
                progress.put("state",result.state()); progress.put("classification",result.classification());
                Response response=post("/api/queue/"+active.get("jobId")+"/heartbeat",Map.of(
                    "workerId",workerId,"leaseToken",active.get("leaseToken"),"progress",progress));
                if(rejected(response))return;
                if(response.status==409){abandon(execution,snapshot);return;}
                requireSuccess(response);queueRetry.success(); lastHeartbeat=System.currentTimeMillis();leaseDeadline=clock.getAsLong()+45_000_000_000L;
            }
                return;
            }
            Map<String,Object> metadata;
            String stdout="",stderr="";
            try {
                if (result.evidencePath()==null) throw new IllegalStateException("Worker did not create an evidence file");
                metadata=mapper.readValue(readBounded(Path.of(result.evidencePath())),Map.class);
                stdout=readBounded(Path.of(result.stdoutPath()));
                stderr=readBounded(Path.of(result.stderrPath()));
                metadata.put("workerEvidencePath",result.evidencePath());
            } catch (Exception exception) { metadata=synthetic("Evidence could not be uploaded: "+exception.getMessage()); }
            finish(metadata,stdout,stderr);
        } catch (Exception exception) {
            queueRetry.failed();
            if (System.currentTimeMillis()-lastWarning>30000) {
                log.warn("Queue connection/reporting failed; will retry: {}",exception.getMessage());
                lastWarning=System.currentTimeMillis();
            }
        }
    }

    private void finish(Map<String,Object> metadata,String stdout,String stderr) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("workerId",workerId); body.put("leaseToken",active.get("leaseToken"));
        body.put("metadata",metadata); body.put("stdout",stdout); body.put("stderr",stderr);
        pendingCompletion=outbox.save(active,body);
        uploadPending();
    }
    private void uploadPending() throws Exception {
        Response response=post("/api/queue/"+pendingCompletion.claim().get("jobId")+"/complete",pendingCompletion.body());
        if(response.status==401||response.status==403){stopForRejectedAccess();return;}
        if(response.status==409){outbox.archive("lease-rejected");clearCompletion();queueRetry.success();return;}
        if(response.status>=300&&response.status<500&&response.status!=408&&response.status!=429){outbox.archive("protocol-rejected");clearCompletion();accessRejected=true;log.warn("Completion rejected; private outbox preserved for inspection. Worker stopped claiming.");return;}
        requireSuccess(response);
        var acknowledgement=mapper.readTree(response.body);
        if(!pendingCompletion.claim().get("jobId").toString().equals(acknowledgement.path("executionId").asText())||!"COMPLETED".equals(acknowledgement.path("queueState").asText()))throw new IllegalStateException("Backend did not acknowledge this exact completed job");
        outbox.archive("acknowledged");
        log.info("Completion acknowledged for execution {}",pendingCompletion.claim().get("executionId"));
        clearCompletion();queueRetry.success();
    }
    private void clearCompletion(){pendingCompletion=null;active=null;submitted=false;}
    private boolean rejected(Response response){if(response.status==401||response.status==403){stopForRejectedAccess();return true;}return false;}
    private void stopForRejectedAccess(){
        accessRejected=true;
        if(active!=null&&submitted&&pendingCompletion==null)abandon(UUID.fromString(active.get("executionId").toString()),UUID.fromString(snapshotId));
        log.warn("Worker authentication/permission rejected; no new claims. Restart with a valid matching credential. Pending completion is retained.");
    }
    private Map<String,Object> synthetic(String message) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("executionId",active.get("executionId")); result.put("snapshotId",active.get("snapshotId"));
        result.put("snapshotContentHash",expectedHash); result.put("operation",active.get("operation"));
        result.put("selectedTest",active.get("testSelector")); result.put("state","ERROR");
        result.put("classification","INFRASTRUCTURE_ERROR"); result.put("technicalError",message);
        result.put("exitCode",null); result.put("evidenceComplete",false);
        return result;
    }
    private void abandon(UUID execution,UUID snapshot) {
        if (submitted) {
            try { engine.submit(new JobRequest(execution,snapshot,Operation.CANCEL_EXECUTION,null,120)); }
            catch (Exception ignored) { /* Backend fencing prevents any stale result from being accepted. */ }
        }
        log.warn("Lease lost for queued execution {}; stale result will not be uploaded",active.get("jobId"));
        active=null; submitted=false;
    }
    private record Response(int status,String body) {}
    private Response post(String path,Object body) throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(backend+path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if(!accessToken.isBlank())builder.header("Authorization","Bearer "+accessToken);
        HttpRequest request=builder.build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(),response.body());
    }
    private static void requireSuccess(Response response) {
        if (response.status<200 || response.status>=300) throw new IllegalStateException("Backend queue HTTP "+response.status);
    }
    private static String readBounded(Path path) throws Exception {
        SnapshotFiles.requireRealPath(path);
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>2097152)
            throw new IllegalArgumentException("Evidence file is missing or exceeds 2 MiB");
        return Files.readString(path);
    }
}
