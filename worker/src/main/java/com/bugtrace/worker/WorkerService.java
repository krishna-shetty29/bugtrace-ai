package com.bugtrace.worker;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.bugtrace.worker.WorkerController.*;

@Service
public class WorkerService {
    public record Result(UUID executionId, UUID snapshotId, Operation operation,
        String state, Integer exitCode, String technicalError, String workspace,
        String logPath, Instant createdAt, Instant finishedAt) {}

    private static final class Execution {
        final JobRequest job;
        final Instant created = Instant.now();
        String state = "QUEUED";
        Integer exitCode;
        String error;
        Path experiment;
        Path log;
        Instant finished;
        Process process;
        Execution(JobRequest job) { this.job = job; }
        synchronized Result result() {
            return new Result(job.executionId(), job.snapshotId(), job.operation(), state,
                exitCode, error, experiment == null ? null : experiment.toString(),
                log == null ? null : log.toString(), created, finished);
        }
    }

    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(2);
    private final String configuredSnapshotId;
    private final String configuredHash;
    private final Path base;
    private final Path workspaceRoot;
    private final Path controlRoot;

    public WorkerService(
        @Value("${worker.snapshot-id}") String snapshotId,
        @Value("${worker.content-hash}") String hash,
        @Value("${worker.base-path}") String basePath,
        @Value("${worker.workspace-root}") String workspaceRoot,
        @Value("${worker.control-root}") String controlRoot
    ) {
        this.configuredSnapshotId = snapshotId;
        this.configuredHash = hash;
        this.base = Path.of(basePath).toAbsolutePath().normalize();
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.controlRoot = Path.of(controlRoot).toAbsolutePath().normalize();
    }

    public Result submit(JobRequest job) {
        if (job.operation() == Operation.CANCEL_EXECUTION) return cancel(job);
        if (!job.snapshotId().toString().equals(configuredSnapshotId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot is not registered with this worker");
        try { MavenCommands.arguments(job.operation(), job.testSelector(), Path.of("pom.xml")); }
        catch (IllegalArgumentException exception) { throw badRequest(exception.getMessage()); }
        if (executions.size() >= 100) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Local worker history limit reached; restart to reset it");
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Two executions are already active");
        Execution execution = new Execution(job);
        if (executions.putIfAbsent(job.executionId(), execution) != null) {
            slots.release();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "executionId already exists");
        }
        executor.submit(() -> run(execution));
        return execution.result();
    }

    public Result result(UUID id) { return lookup(id).result(); }

    private Execution lookup(UUID id) {
        Execution execution = executions.get(id);
        if (execution == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        return execution;
    }

    private Result cancel(JobRequest job) {
        Execution execution = lookup(job.executionId());
        synchronized (execution) {
            if (!execution.job.snapshotId().equals(job.snapshotId())) throw badRequest("snapshotId does not match execution");
            if (List.of("QUEUED", "PREPARING", "RUNNING").contains(execution.state)) {
                execution.state = "CANCEL_REQUESTED";
                if (execution.process != null) stopTree(execution.process);
            }
            return execution.result();
        }
    }

    private void run(Execution execution) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(execution.job.timeoutSeconds());
        try {
            synchronized (execution) {
                if (execution.state.equals("CANCEL_REQUESTED")) { finishCancelled(execution); return; }
                execution.state = "PREPARING";
            }
            if (!configuredHash.matches("[0-9a-f]{64}") || !base.startsWith(workspaceRoot)
                || !base.getFileName().toString().equals("base"))
                throw new IllegalArgumentException("Configure a registered Step 14 base and SHA-256 hash");
            SnapshotFiles.requireRealPath(base);
            SnapshotFiles.requireRealPath(controlRoot);
            if (!Files.isRegularFile(controlRoot.resolve("mvnw.cmd")))
                throw new IllegalArgumentException("Worker's trusted Maven Wrapper is missing");
            if (!SnapshotFiles.hash(base).equals(configuredHash))
                throw new IllegalArgumentException("Base snapshot hash mismatch");
            Path home = base.getParent();
            SnapshotFiles.requireRealPath(home.resolve("experiments"));
            SnapshotFiles.requireRealPath(home.resolve("artifacts"));
            Path experiment = home.resolve("experiments").resolve(execution.job.executionId().toString());
            Path artifact = home.resolve("artifacts").resolve(execution.job.executionId().toString());
            Files.createDirectory(artifact);
            Path log = artifact.resolve("worker.log");
            synchronized (execution) { execution.experiment = experiment; execution.log = log; }
            SnapshotFiles.copy(base, experiment);
            if (!SnapshotFiles.hash(experiment).equals(configuredHash))
                throw new IllegalArgumentException("Experiment copy hash mismatch");
            var command = MavenCommands.windowsCommand(execution.job.operation(), execution.job.testSelector(), experiment.resolve("pom.xml"));
            Process process;
            synchronized (execution) {
                if (execution.state.equals("CANCEL_REQUESTED")) { finishCancelled(execution); return; }
                if (System.nanoTime() >= deadline) { execution.state = "TIMED_OUT"; execution.finished = Instant.now(); return; }
                ProcessBuilder builder = new ProcessBuilder(command).directory(controlRoot.toFile()).redirectErrorStream(true);
                // Do not pass backend database or API credentials into test processes.
                Set<String> allowed = Set.of("PATH", "JAVA_HOME", "USERPROFILE", "HOME", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "PATHEXT", "APPDATA", "LOCALAPPDATA");
                builder.environment().keySet().removeIf(k -> !allowed.contains(k.toUpperCase(Locale.ROOT)));
                process = builder.start();
                execution.process = process;
                execution.state = "RUNNING";
            }
            Future<?> drain = executor.submit(() -> drain(process, log));
            long remaining = Math.max(1, deadline - System.nanoTime());
            boolean completed = process.waitFor(remaining, TimeUnit.NANOSECONDS);
            if (!completed) stopTree(process);
            try { drain.get(5, TimeUnit.SECONDS); }
            catch (TimeoutException exception) { process.getInputStream().close(); drain.cancel(true); }
            synchronized (execution) {
                if (execution.state.equals("CANCEL_REQUESTED")) finishCancelled(execution);
                else if (!completed) { execution.state = "TIMED_OUT"; execution.finished = Instant.now(); }
                else {
                    execution.exitCode = process.exitValue();
                    execution.state = execution.exitCode == 0 ? "SUCCEEDED" : "FAILED";
                    execution.finished = Instant.now();
                }
            }
        } catch (Exception exception) {
            synchronized (execution) {
                if (execution.process != null) stopTree(execution.process);
                if (execution.state.equals("CANCEL_REQUESTED")) finishCancelled(execution);
                else { execution.state = "ERROR"; execution.error = exception.getMessage(); execution.finished = Instant.now(); }
            }
        } finally { slots.release(); }
    }

    private void drain(Process process, Path log) {
        try (InputStream in = process.getInputStream(); OutputStream out = Files.newOutputStream(log, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int written = 0;
            boolean truncated = false;
            int n;
            while ((n = in.read(buffer)) != -1) {
                int keep = Math.min(n, Math.max(0, 2 * 1024 * 1024 - written));
                out.write(buffer, 0, keep);
                written += keep;
                if (keep < n && !truncated) {
                    out.write("\n[Worker log truncated at 2 MiB]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    truncated = true;
                }
            }
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private static void finishCancelled(Execution execution) {
        execution.state = "CANCELLED";
        execution.finished = Instant.now();
    }

    private static void stopTree(Process process) {
        // Best-effort local cleanup. OS-level containment is required for hostile code.
        var descendants = process.descendants().toList();
        for (var child : descendants) child.destroyForcibly();
        process.destroyForcibly();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @PreDestroy public void shutdown() {
        for (Execution execution : executions.values()) {
            synchronized (execution) { if (execution.process != null) stopTree(execution.process); }
        }
        executor.shutdownNow();
    }
}
