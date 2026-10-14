package com.bugtrace.worker;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkerServiceTest {
    @Test void constrainsPreparationAndExecution() {
        for (String goal : List.of("test-compile", "test", "compile")) {
            var args = DockerCommands.create("bugtrace-1234", "maven@sha256:" + "a".repeat(64), Path.of("experiment"), Path.of("cache"), List.of(goal));
            for (String flag : List.of("--user", "--cpus", "--memory", "--memory-swap", "--pids-limit", "--read-only", "--network", "--cap-drop", "--security-opt", "--tmpfs")) assertTrue(args.contains(flag));
            assertEquals("1000:1000", args.get(args.indexOf("--user") + 1));
            assertEquals("none", args.get(args.indexOf("--network") + 1));
            assertTrue(args.contains("-o"));
            assertEquals(goal, args.getLast());
            assertFalse(String.join(" ", args).contains("docker.sock"));
            assertFalse(args.contains("--privileged"));
            assertFalse(args.contains("--env-file"));
        }
    }
    @Test void rejectsUnpinnedImage() {
        assertThrows(IllegalArgumentException.class, () -> DockerCommands.create("bugtrace-1234", "maven:latest", Path.of("experiment"), Path.of("cache"), List.of("test")));
    }
}
