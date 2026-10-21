package com.bugtrace.backend;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_SNAPSHOT_LOCAL_TEST",matches="true")
class BenchmarkSnapshotLocalTest {
    @TempDir Path temp;
    private Path repo(){String override=System.getenv("BENCHMARK_TEST_REPOSITORY");return override==null?Path.of(System.getProperty("user.home"),"bbp"):Path.of(override);}
    private List<BenchmarkCatalog.Registration> registrations()throws Exception {return new BenchmarkCatalog(repo(),"6c4f8856bb7e4d49e953a8b086d594d2f505838d",BenchmarkCatalog.MANIFEST).validate();}
    @AfterEach void writableCleanup()throws Exception {try(var walk=Files.walk(temp)){for(Path file:walk.filter(Files::isRegularFile).toList())if(Files.getFileStore(file).supportsFileAttributeView("dos"))Files.setAttribute(file,"dos:readonly",false);}}
    private GitSnapshot.Capture capture(BenchmarkCatalog.Registration c,UUID id)throws Exception {return GitSnapshot.capture(repo(),c.releaseCommit(),c.modulePath(),temp.resolve("workspace"),id);}
    @Test void capturesOnlyCaseModuleWithTwoHashConventions()throws Exception {
        var c=registrations().getFirst();var capture=capture(c,UUID.randomUUID());
        assertEquals(c.snapshotContentHash(),BenchmarkSnapshotFiles.verify(capture.base(),c));
        assertEquals(capture.contentHash(),GitSnapshot.contentHash(capture.base()));
        assertFalse(Files.exists(capture.base().resolve(".git")));assertFalse(Files.exists(capture.base().resolve("cases")));
        assertTrue(Files.isDirectory(capture.base().getParent().resolve("experiments")));
        try(var walk=Files.walk(capture.base())){assertEquals(4,walk.filter(Files::isRegularFile).count());}
    }
    @Test void rejectsCrossCaseVerification()throws Exception {var cases=registrations();var capture=capture(cases.getFirst(),UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->BenchmarkSnapshotFiles.verify(capture.base(),cases.get(1)));}
    @Test void rejectsExtraCapturedFile()throws Exception {var c=registrations().getFirst();var capture=capture(c,UUID.randomUUID());Files.writeString(capture.base().resolve("unexpected.txt"),"extra");assertThrows(IllegalArgumentException.class,()->BenchmarkSnapshotFiles.verify(capture.base(),c));}
    @Test void refusesToOverwriteExistingOwnerWorkspace()throws Exception {var c=registrations().getFirst();UUID owner=UUID.randomUUID();var capture=capture(c,owner);assertThrows(Exception.class,()->capture(c,owner));assertEquals(c.snapshotContentHash(),BenchmarkSnapshotFiles.verify(capture.base(),c));}
    @Test void detectsModifiedCapturedSource()throws Exception {var c=registrations().getFirst();var capture=capture(c,UUID.randomUUID());Path source=capture.base().resolve(c.allowedProductionPaths().getFirst());if(Files.getFileStore(source).supportsFileAttributeView("dos"))Files.setAttribute(source,"dos:readonly",false);Files.writeString(source,"modified");assertThrows(IllegalArgumentException.class,()->BenchmarkSnapshotFiles.verify(capture.base(),c));}
}
