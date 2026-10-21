package com.bugtrace.worker;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ReconnectStorageTest {
 @TempDir Path temp;
 @Test void retriesUseMonotonicBackoffCappedAtThirtySeconds(){
  var time=new AtomicLong();var retry=new RetryBackoff(time::get);assertTrue(retry.ready());
  for(int seconds:new int[]{1,2,4,8,16,30,30}){retry.failed();assertFalse(retry.ready());time.addAndGet(seconds*1_000_000_000L-1);assertFalse(retry.ready());time.incrementAndGet();assertTrue(retry.ready());}
  retry.success();retry.failed();time.addAndGet(1_000_000_000L);assertTrue(retry.ready());
 }
 @Test void completionSurvivesNewStoreAndIsArchivedOnlyExplicitly() throws Exception {
  UUID snapshot=UUID.randomUUID(),attempt=UUID.randomUUID(),lease=UUID.randomUUID();
  var claim=new LinkedHashMap<String,Object>();claim.put("jobId",UUID.randomUUID().toString());claim.put("snapshotId",snapshot.toString());claim.put("executionId",attempt.toString());claim.put("leaseToken",lease.toString());
  var body=new LinkedHashMap<String,Object>();body.put("workerId","worker");body.put("leaseToken",lease.toString());body.put("metadata",Map.of("classification","PASSED"));body.put("stdout","exact output");body.put("stderr","");
  var store=new CompletionOutbox(temp,"https://backend.example","worker",snapshot.toString());store.save(claim,body);
  var restarted=new CompletionOutbox(temp,"https://backend.example","worker",snapshot.toString());assertEquals(body,restarted.load().body());
  var changed=new LinkedHashMap<>(body);changed.put("stdout","changed");assertThrows(IllegalStateException.class,()->restarted.save(claim,changed));assertEquals(body,restarted.load().body());
  restarted.archive("acknowledged");assertNull(restarted.load());assertTrue(Files.isRegularFile(restarted.directory().resolve(attempt+"-acknowledged.json")));
  assertNull(new CompletionOutbox(temp,"https://other.example","worker",snapshot.toString()).load());
 }
}
