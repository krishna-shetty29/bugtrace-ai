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
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper mapper=JsonMapper.builder().build();
    private Map<String,Object> active;
    private long lastHeartbeat,lastWarning;
    private boolean submitted;

    public QueueClient(WorkerService engine,@Value("${worker.queue.backend-url}") String backend,
        @Value("${worker.queue.worker-id}") String workerId,@Value("${worker.snapshot-id}") String snapshotId,
        @Value("${worker.content-hash}") String expectedHash) {
        this.engine=engine; this.backend=backend; this.workerId=workerId;
        this.snapshotId=snapshotId; this.expectedHash=expectedHash;
        URI uri=URI.create(backend);
        if (!Set.of("127.0.0.1","localhost").contains(uri.getHost()) || !uri.getScheme().equals("http"))
            throw new IllegalArgumentException("This development queue client only connects to a localhost HTTP backend");
        if (!workerId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid worker ID");
    }

    @Scheduled(fixedDelay=1000)
    public synchronized void poll() {
        try {
            if (active==null) {
                UUID.fromString(snapshotId); // Fail closed if operator registration is missing.
                Response response=post("/api/queue/claim",Map.of("workerId",workerId,"snapshotId",snapshotId));
                if (response.status==204) return;
                requireSuccess(response);
                active=mapper.readValue(response.body,Map.class);
                submitted=false; lastHeartbeat=0;
                log.info("Claimed queued execution {} attempt {}",active.get("jobId"),active.get("attempt"));
            }
            UUID execution=UUID.fromString(active.get("executionId").toString());
            UUID snapshot=UUID.fromString(active.get("snapshotId").toString());
            if (!submitted) {
                try {
                    engine.submit(new JobRequest(execution,snapshot,Operation.valueOf(active.get("operation").toString()),
                        (String)active.get("testSelector"),((Number)active.get("timeoutSeconds")).intValue()));
                    submitted=true;
                } catch (Exception exception) {
                    // Report an explicit setup failure instead of silently dropping the claim.
                    finish(synthetic("Worker could not start attempt: "+exception.getMessage()),"","");
                    return;
                }
            }
            var result=engine.result(execution);
            if (System.currentTimeMillis()-lastHeartbeat>=5000) {
                Map<String,Object> progress=new LinkedHashMap<>();
                progress.put("state",result.state()); progress.put("classification",result.classification());
                Response response=post("/api/queue/"+active.get("jobId")+"/heartbeat",Map.of(
                    "workerId",workerId,"leaseToken",active.get("leaseToken"),"progress",progress));
                if (response.status==409) { abandon(execution,snapshot); return; }
                requireSuccess(response); lastHeartbeat=System.currentTimeMillis();
            }
            if (List.of("QUEUED","PREPARING","RUNNING","CANCEL_REQUESTED").contains(result.state())) return;
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
        Response response=post("/api/queue/"+active.get("jobId")+"/complete",body);
        if (response.status==409) {
            abandon(UUID.fromString(active.get("executionId").toString()),UUID.fromString(active.get("snapshotId").toString()));
            return;
        }
        requireSuccess(response);
        log.info("Stored queued execution {} result {}",active.get("jobId"),metadata.get("classification"));
        active=null; submitted=false;
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
        HttpRequest request=HttpRequest.newBuilder(URI.create(backend+path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
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
