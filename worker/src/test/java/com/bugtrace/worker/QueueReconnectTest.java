package com.bugtrace.worker;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import com.bugtrace.worker.WorkerController.*;
class QueueReconnectTest {
 @TempDir Path temp;
 static class Engine extends WorkerService {
  final UUID snapshot,attempt;final Path evidence,stdout,stderr;int submissions;
  Engine(Path temp,UUID snapshot,UUID attempt) throws Exception {
   super(snapshot.toString(),SnapshotFiles.hash(base(temp)),base(temp).toString(),temp.toString(),temp.toString(),"unused");this.snapshot=snapshot;this.attempt=attempt;
   Path folder=Files.createDirectories(temp.resolve("evidence"));evidence=folder.resolve("execution.json");stdout=folder.resolve("stdout.log");stderr=folder.resolve("stderr.log");
   if(!Files.exists(evidence)){Files.writeString(stdout,"original log");Files.writeString(stderr,"");ExecutionEvidence.write(folder,Map.of("classification","PASSED","exitCode",0));}
  }
  static Path base(Path temp) throws Exception {return Files.createDirectories(temp.resolve("base"));}
  @Override public Result submit(JobRequest job){submissions++;return result(attempt);}
  @Override public Result result(UUID id){return new Result(attempt,snapshot,Operation.RUN_SELECTED_TEST,"SUCCEEDED",0,null,null,null,Instant.now(),Instant.now(),"PASSED",evidence.toString(),stdout.toString(),stderr.toString());}
 }
 @Test void retriesExactCompletionAfterUncertainResponseWithoutResubmitting() throws Exception {run(false,500);}
 @Test void incompleteAcknowledgementKeepsPendingPayload() throws Exception {run(false,201);}
 @Test void restartUploadsPendingPayloadWithoutSubmittingExperiment() throws Exception {run(true,500);}
 @Test void revokedCompletionRetainsOutboxAndStopsClaims() throws Exception {run(false,403);}
 @Test void expiredLeasePreservesRejectedEvidence() throws Exception {run(false,409);}
 private void run(boolean restart,int firstStatus) throws Exception {
  UUID snapshot=UUID.randomUUID(),attempt=UUID.randomUUID(),job=UUID.randomUUID(),lease=UUID.randomUUID();var engine=new Engine(temp,snapshot,attempt);String hash=SnapshotFiles.hash(temp.resolve("base"));
  var time=new AtomicLong();var claims=new AtomicInteger();var completions=new AtomicInteger();var heartbeats=new AtomicInteger();var first=new AtomicReference<String>();var changed=new AtomicBoolean();
  var mapper=JsonMapper.builder().build();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   String path=exchange.getRequestURI().getPath();int status=200;String response="{}";
   if(path.endsWith("/claim")){claims.incrementAndGet();response=mapper.writeValueAsString(Map.of("jobId",job.toString(),"executionId",attempt.toString(),"snapshotId",snapshot.toString(),"leaseToken",lease.toString(),"operation","RUN_SELECTED_TEST","testSelector","CheckoutIdempotencyTest","timeoutSeconds",120,"attempt",1,"leaseSeconds",45));}
   else if(path.endsWith("/heartbeat")){heartbeats.incrementAndGet();status=409;}
   else if(path.endsWith("/complete")){String text=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);int n=completions.incrementAndGet();if(n==1){first.set(text);status=firstStatus;}else if(!first.get().equals(text))changed.set(true);response=mapper.writeValueAsString(Map.of("executionId",job.toString(),"queueState",n==1&&firstStatus==201?"QUEUED":"COMPLETED"));}
   byte[] bytes=response.getBytes();exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });server.start();
  String origin="http://127.0.0.1:"+server.getAddress().getPort();
  try{
   var client=new QueueClient(engine,origin,"worker",snapshot.toString(),hash,"k".repeat(43),time::get);client.poll();assertEquals(1,engine.submissions);assertEquals(1,claims.get());assertEquals(1,completions.get());assertEquals(0,heartbeats.get());
   var store=new CompletionOutbox(engine.queueStateRoot(),origin,"worker",snapshot.toString());
   if(firstStatus==409){assertNull(store.load());assertTrue(Files.isRegularFile(store.directory().resolve(attempt+"-lease-rejected.json")));return;}
   assertNotNull(store.load());client.poll();assertEquals(1,completions.get());
   if(firstStatus==403){time.addAndGet(60_000_000_000L);client.poll();assertEquals(1,completions.get());assertEquals(1,claims.get());return;}
   if(restart){var fresh=new Engine(temp,snapshot,attempt);new QueueClient(fresh,origin,"worker",snapshot.toString(),hash,"k".repeat(43),time::get).poll();assertEquals(0,fresh.submissions);fresh.shutdown();}
   else{time.addAndGet(1_000_000_000L);client.poll();}
   assertEquals(2,completions.get());assertFalse(changed.get());assertEquals(1,engine.submissions);assertEquals(1,claims.get());assertEquals(0,heartbeats.get());assertNull(store.load());
  }finally{server.stop(0);engine.shutdown();}
 }
}
