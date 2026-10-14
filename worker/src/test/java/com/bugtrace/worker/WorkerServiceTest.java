package com.bugtrace.worker;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.bugtrace.worker.WorkerController.*;

class WorkerServiceTest {
    @TempDir Path temp;

    private WorkerService worker(String script, UUID snapshotId) throws Exception {
        Path root = temp.resolve("workspace");
        Path home = root.resolve(UUID.randomUUID().toString());
        Path base = home.resolve("base");
        Files.createDirectories(base);
        Files.createDirectory(home.resolve("experiments"));
        Files.createDirectory(home.resolve("artifacts"));
        Files.writeString(base.resolve("pom.xml"), "<project/>");
        Path control = Files.createDirectory(temp.resolve("control"));
        Files.writeString(control.resolve("Sleep.java"), "class Sleep { public static void main(String[] args) throws Exception { Thread.sleep(20000); } }");
        // Test-only trusted wrapper: no Maven dependency/network needed.
        Files.writeString(control.resolve("mvnw.cmd"), script);
        return new WorkerService(snapshotId.toString(), SnapshotFiles.hash(base),
            base.toString(), root.toString(), control.toString());
    }

    private WorkerService.Result await(WorkerService worker, UUID id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var result = worker.result(id);
            if (!java.util.List.of("QUEUED", "PREPARING", "RUNNING", "CANCEL_REQUESTED").contains(result.state())) return result;
            Thread.sleep(50);
        }
        throw new AssertionError("Execution did not finish");
    }

    @Test void recordsNonzeroExitWithoutChangingBase() throws Exception {
        UUID snapshot = UUID.randomUUID();
        WorkerService worker = worker("@echo off\r\necho fixture failure\r\nexit /b 1\r\n", snapshot);
        try {
            UUID id = UUID.randomUUID();
            worker.submit(new JobRequest(id, snapshot, Operation.RUN_TEST_SUITE, null, 10));
            var result = await(worker, id);
            assertEquals("FAILED", result.state(), result.toString());
            assertEquals(1, result.exitCode());
            assertNull(result.technicalError());
            assertTrue(Files.readString(Path.of(result.logPath())).contains("fixture failure"));
        } finally { worker.shutdown(); }
    }

    @Test void timesOutAndCancelsLongRunningJobs() throws Exception {
        UUID snapshot = UUID.randomUUID();
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        WorkerService worker = worker("@echo off\r\n\"" + java + "\" Sleep.java\r\nexit /b 0\r\n", snapshot);
        try {
            UUID timed = UUID.randomUUID();
            worker.submit(new JobRequest(timed, snapshot, Operation.COMPILE, null, 1));
            var timedResult = await(worker, timed);
            assertEquals("TIMED_OUT", timedResult.state(), timedResult.toString());
            UUID cancelled = UUID.randomUUID();
            worker.submit(new JobRequest(cancelled, snapshot, Operation.COMPILE, null, 10));
            worker.submit(new JobRequest(cancelled, snapshot, Operation.CANCEL_EXECUTION, null, 10));
            assertEquals("CANCELLED", await(worker, cancelled).state());
        } finally { worker.shutdown(); }
    }
}
