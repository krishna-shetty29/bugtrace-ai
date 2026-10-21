package com.bugtrace.worker;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class WorkerLocalSnapshotTest {
 @TempDir Path temp;
 @Test void refusesClaimsAfterLocalBytesChange() throws Exception {
  Path base=temp.resolve("laptop-base");Files.createDirectory(base);Files.writeString(base.resolve("pom.xml"),"original");
  var engine=new WorkerService(UUID.randomUUID().toString(),SnapshotFiles.hash(base),base.toString(),temp.toString(),temp.toString(),"unused-image");
  assertDoesNotThrow(engine::verifyRegisteredBaseBeforeClaim);
  Files.writeString(base.resolve("pom.xml"),"changed");
  assertThrows(IllegalArgumentException.class,engine::verifyRegisteredBaseBeforeClaim);
 }
}
