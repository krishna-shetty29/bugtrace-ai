package com.bugtrace.worker;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class WorkerPresenceClientTest {
 @TempDir Path temp;
 @Test void reportsWhileIdleWithoutClaimingWork() throws Exception {check(200,false);}
 @Test void rejectedCredentialStopsFurtherClaims() throws Exception {check(401,false);}
 @Test void reportsNotReadyForChangedBase() throws Exception {check(200,true);}
 private void check(int status,boolean tamper) throws Exception {
  Path base=Files.createDirectory(temp.resolve("base"));Files.writeString(base.resolve("sample.txt"),"original");
  String hash=SnapshotFiles.hash(base);UUID snapshot=UUID.randomUUID();
  var engine=new WorkerService(snapshot.toString(),hash,base.toString(),temp.toString(),temp.toString(),"unused-image");
  AtomicInteger presence=new AtomicInteger(),claims=new AtomicInteger();AtomicReference<Map> body=new AtomicReference<>();
  var mapper=JsonMapper.builder().build();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   int code;
   if(exchange.getRequestURI().getPath().equals("/api/workers/presence")){
    presence.incrementAndGet();body.set(mapper.readValue(exchange.getRequestBody().readAllBytes(),Map.class));code=status;
   }else{claims.incrementAndGet();code=204;}
   exchange.sendResponseHeaders(code,code==204?-1:2);
   if(code!=204)exchange.getResponseBody().write("{}".getBytes());exchange.close();
  });server.start();
  try{
   var client=new QueueClient(engine,"http://127.0.0.1:"+server.getAddress().getPort(),"test-worker",snapshot.toString(),hash,"k".repeat(43));
   if(tamper)Files.writeString(base.resolve("sample.txt"),"changed");
   client.reportPresence();assertEquals(1,presence.get());assertEquals(0,claims.get());
   assertEquals(tamper?"NOT_READY":"BASE_VERIFIED",body.get().get("readiness"));assertNull(body.get().get("currentExecutionId"));
   assertFalse(body.get().containsKey("lastContactAt"));
   if(status==401||tamper){client.poll();assertEquals(0,claims.get());}
  }finally{server.stop(0);engine.shutdown();}
 }
}
