package com.bugtrace.worker;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
/** Private local completion payloads; never stores the worker access key. */
final class CompletionOutbox {
 record Pending(Map<String,Object> claim,Map<String,Object> body) {}
 private final Path directory;private final String origin,worker,snapshot;
 private final JsonMapper json=JsonMapper.builder().build();
 CompletionOutbox(Path root,String origin,String worker,String snapshot){this.origin=origin;this.worker=worker;this.snapshot=snapshot;directory=root.resolve(GeneratedTestOverlay.sha(origin+"\n"+worker+"\n"+snapshot));}
 Path directory(){return directory;}
 private Path pending(){return directory.resolve("pending.json");}
 private void prepare() throws Exception {
  Path ancestor=directory.toAbsolutePath().normalize();while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();SnapshotFiles.requireRealPath(ancestor);
  Files.createDirectories(directory);SnapshotFiles.requireRealPath(directory);
 }
 Pending load() throws Exception {
  if(!Files.exists(pending(),LinkOption.NOFOLLOW_LINKS))return null;
  SnapshotFiles.requireRealPath(pending());if(!Files.isRegularFile(pending(),LinkOption.NOFOLLOW_LINKS)||Files.size(pending())>16*1024*1024)throw new IllegalArgumentException("Invalid completion outbox");
  var doc=json.readTree(Files.readAllBytes(pending()));
  if(!origin.equals(doc.path("origin").asText())||!worker.equals(doc.path("workerId").asText())||!snapshot.equals(doc.path("snapshotId").asText()))throw new IllegalArgumentException("Outbox binding differs");
  Map<String,Object> claim=json.convertValue(doc.path("claim"),Map.class),body=json.convertValue(doc.path("body"),Map.class);
  UUID.fromString(Objects.toString(claim.get("jobId"),""));UUID.fromString(Objects.toString(claim.get("executionId"),""));UUID.fromString(Objects.toString(claim.get("leaseToken"),""));
  if(!snapshot.equals(claim.get("snapshotId"))||!worker.equals(body.get("workerId"))||!Objects.equals(claim.get("leaseToken"),body.get("leaseToken")))throw new IllegalArgumentException("Outbox claim differs");
  return new Pending(claim,body);
 }
 Pending save(Map<String,Object> claim,Map<String,Object> body) throws Exception {
  prepare();var previous=load();
  if(previous!=null){if(!json.valueToTree(previous.claim()).equals(json.valueToTree(claim))||!json.valueToTree(previous.body()).equals(json.valueToTree(body)))throw new IllegalStateException("Another completion is pending; refusing overwrite");return previous;}
  var doc=Map.of("origin",origin,"workerId",worker,"snapshotId",snapshot,"claim",claim,"body",body);
  byte[] bytes=json.writeValueAsBytes(doc);if(bytes.length>16*1024*1024)throw new IllegalArgumentException("Completion outbox exceeds size limit");
  Path staging=directory.resolve("pending-"+UUID.randomUUID()+".tmp");
  try(var channel=java.nio.channels.FileChannel.open(staging,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
  Files.move(staging,pending(),StandardCopyOption.ATOMIC_MOVE);
  return load();
 }
 void archive(String outcome) throws Exception {
  var item=Objects.requireNonNull(load());
  if(!Set.of("acknowledged","lease-rejected","protocol-rejected").contains(outcome))throw new IllegalArgumentException("Invalid outbox outcome");
  Path target=directory.resolve(item.claim().get("executionId")+"-"+outcome+".json");
  if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
   SnapshotFiles.requireRealPath(target);if(Files.mismatch(pending(),target)!=-1)throw new IllegalStateException("Outbox archive differs");Files.delete(pending());
  }else Files.move(pending(),target,StandardCopyOption.ATOMIC_MOVE);
 }
}
