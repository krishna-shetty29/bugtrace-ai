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
    public record Result(UUID executionId, UUID snapshotId, Operation operation, String state,
        Integer exitCode, String technicalError, String workspace, String logPath, Instant createdAt, Instant finishedAt) {}
    private static class Execution {
        final JobRequest job;
        final Instant created = Instant.now();
        String state = "QUEUED";
        Integer exitCode;
        String error;
        Path experiment, log, cache;
        String container;
        Process process;
        Instant finished;
        Execution(JobRequest job) { this.job = job; }
        synchronized Result result() { return new Result(job.executionId(), job.snapshotId(), job.operation(), state,
            exitCode, error, experiment == null ? null : experiment.toString(), log == null ? null : log.toString(), created, finished); }
    }
    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(2);
    private final String snapshotId, hash, image;
    private final Path base, root, cacheSeed;

    public WorkerService(@Value("${worker.snapshot-id}") String snapshotId,
        @Value("${worker.content-hash}") String hash, @Value("${worker.base-path}") String base,
        @Value("${worker.workspace-root}") String root, @Value("${worker.cache-seed}") String cache,
        @Value("${worker.container-image}") String image) {
        this.snapshotId = snapshotId; this.hash = hash; this.image = image;
        this.base = Path.of(base).toAbsolutePath().normalize();
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.cacheSeed = Path.of(cache).toAbsolutePath().normalize();
    }
    public Result result(UUID id) { return lookup(id).result(); }
    private Execution lookup(UUID id) {
        var execution = executions.get(id);
        if (execution == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        return execution;
    }
    public Result submit(JobRequest job) {
        if (job.operation() == Operation.CANCEL_EXECUTION) {
            Execution execution = lookup(job.executionId());
            synchronized (execution) {
                if (!execution.job.snapshotId().equals(job.snapshotId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot mismatch");
                if (List.of("QUEUED", "PREPARING", "RUNNING").contains(execution.state)) execution.state = "CANCEL_REQUESTED";
            }
            return execution.result();
        }
        if (!job.snapshotId().toString().equals(snapshotId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not registered");
        try { MavenCommands.arguments(job.operation(), job.testSelector(), Path.of("pom.xml")); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
        if (executions.size() >= 100 || !slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Worker capacity reached");
        var execution = new Execution(job);
        if (executions.putIfAbsent(job.executionId(), execution) != null) {
            slots.release(); throw new ResponseStatusException(HttpStatus.CONFLICT, "executionId already exists");
        }
        executor.submit(() -> run(execution));
        return execution.result();
    }
    private boolean cancelled(Execution e) { synchronized (e) { return e.state.equals("CANCEL_REQUESTED"); } }
    private void run(Execution e) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(e.job.timeoutSeconds());
        String terminal = "ERROR";
        try {
            if (cancelled(e)) { terminal = "CANCELLED"; return; }
            synchronized (e) { if (!cancelled(e)) e.state = "PREPARING"; }
            if (!hash.matches("[0-9a-f]{64}") || !base.startsWith(root) || !base.getFileName().toString().equals("base"))
                throw new IllegalArgumentException("Configure a Step 14 snapshot and hash");
            if (!SnapshotFiles.hash(base).equals(hash)) throw new IllegalArgumentException("Base hash mismatch");
            Path home = base.getParent();
            SnapshotFiles.requireRealPath(home.resolve("experiments"));
            SnapshotFiles.requireRealPath(home.resolve("artifacts"));
            Path artifact = home.resolve("artifacts").resolve(e.job.executionId().toString());
            Files.createDirectory(artifact);
            synchronized (e) {
                e.log = artifact.resolve("worker.log");
                e.cache = artifact.resolve("maven-cache");
                e.experiment = home.resolve("experiments").resolve(e.job.executionId().toString());
            }
            Files.createFile(e.log);
            SnapshotFiles.copy(base, e.experiment);
            if (!SnapshotFiles.hash(e.experiment).equals(hash)) throw new IllegalArgumentException("Experiment copy hash mismatch");
            copyCache(e, deadline);
            // Dependency/class preparation executes plugins only inside the restricted container.
            int code = stage(e, List.of("test-compile"), deadline);
            if (code == 0 && !cancelled(e)) {
                var goals = new ArrayList<String>();
                if (e.job.operation() == Operation.RUN_SELECTED_TEST) {
                    goals.add("-Dtest=" + e.job.testSelector());
                    goals.add("-DfailIfNoTests=true");
                    goals.add("-Dsurefire.failIfNoSpecifiedTests=true");
                }
                goals.add(e.job.operation() == Operation.COMPILE ? "compile" : "test");
                code = stage(e, goals, deadline);
            }
            synchronized (e) { e.exitCode = code >= 0 ? code : null; }
            terminal = cancelled(e) ? "CANCELLED" : code == -1 ? "TIMED_OUT" : code == 0 ? "SUCCEEDED" : "FAILED";
        } catch (CancellationException exception) { terminal = "CANCELLED"; }
        catch (TimeoutException exception) { terminal = "TIMED_OUT"; }
        catch (Exception exception) { synchronized (e) { e.error = exception.getMessage(); } }
        finally {
            try { if (e.container != null) cleanup(e.container); }
            catch (Exception exception) { terminal = "ERROR"; synchronized (e) { e.error = "Container cleanup failed: " + exception.getMessage(); } }
            try { if (e.cache != null) deleteCache(e.cache); }
            catch (Exception exception) { terminal = "ERROR"; synchronized (e) { e.error = "Cache cleanup failed: " + exception.getMessage(); } }
            synchronized (e) {
                if (cancelled(e) && !terminal.equals("ERROR")) terminal = "CANCELLED";
                e.state = terminal; e.finished = Instant.now();
            }
            slots.release();
        }
    }
    private int stage(Execution e, List<String> goals, long deadline) throws Exception {
        if (cancelled(e)) throw new CancellationException();
        String name = "bugtrace-" + UUID.randomUUID();
        synchronized (e) { e.container = name; }
        try {
            int created = command(e, DockerCommands.create(name, image, e.experiment, e.cache, goals), deadline);
            if (created == -1) return -1;
            if (created != 0) throw new IOException("Docker create failed; inspect worker.log");
            if (cancelled(e)) throw new CancellationException();
            synchronized (e) { if (!cancelled(e)) e.state = "RUNNING"; }
            int attached = command(e, List.of("docker", "start", "--attach", name), deadline);
            if (attached == -1 || cancelled(e)) return attached;
            Process inspect = new ProcessBuilder("docker", "inspect", "--format",
                "{{.State.Running}} {{.State.ExitCode}} {{.State.OOMKilled}}", name)
                .redirectErrorStream(true).start();
            if (!inspect.waitFor(5, TimeUnit.SECONDS)) { inspect.destroyForcibly(); throw new IOException("Container state inspection timed out"); }
            String state = new String(inspect.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8).strip();
            String[] values = state.split(" ");
            if (inspect.exitValue() != 0 || values.length != 3 || !values[0].equals("false"))
                throw new IOException("Could not confirm container completion");
            if (values[2].equals("true")) synchronized (e) { e.error = "Container exceeded its memory limit (OOM killed)"; }
            return Integer.parseInt(values[1]);
        } finally { cleanup(name); synchronized (e) { e.container = null; } }
    }
    private int command(Execution e, List<String> args, long deadline) throws Exception {
        if (System.nanoTime() >= deadline) return -1;
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        synchronized (e) { e.process = p; }
        Future<?> drain = executor.submit(() -> drain(p, e.log));
        try {
            while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled(e)) { p.destroyForcibly(); throw new CancellationException(); }
                if (System.nanoTime() >= deadline) { p.destroyForcibly(); return -1; }
            }
            drain.get(5, TimeUnit.SECONDS);
            return p.exitValue();
        } finally { p.destroyForcibly(); drain.cancel(true); synchronized (e) { e.process = null; } }
    }
    private static void drain(Process p, Path log) {
        try (var input = p.getInputStream(); var out = Files.newOutputStream(log, StandardOpenOption.APPEND)) {
            long written = Files.size(log);
            byte[] bytes = new byte[8192];
            int n;
            while ((n = input.read(bytes)) != -1) {
                int keep = (int) Math.min(n, Math.max(0, 2L * 1024 * 1024 - written));
                out.write(bytes, 0, keep); written += keep;
            }
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    private void copyCache(Execution e, long deadline) throws Exception {
        SnapshotFiles.requireRealPath(cacheSeed);
        Files.createDirectory(e.cache);
        long total = 0;
        try (var paths = Files.walk(cacheSeed)) {
            for (Path source : paths.toList()) {
                if (cancelled(e)) throw new CancellationException();
                if (System.nanoTime() >= deadline) throw new TimeoutException();
                SnapshotFiles.requireRealPath(source);
                Path destination = e.cache.resolve(cacheSeed.relativize(source)).normalize();
                if (!destination.startsWith(e.cache)) throw new IOException("Cache path escape");
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else if (Files.isRegularFile(source)) {
                    total += Files.size(source);
                    if (total > 3L * 1024 * 1024 * 1024) throw new IOException("Cache exceeds 3 GiB");
                    Files.copy(source, destination);
                    if (Files.getFileStore(destination).supportsFileAttributeView("dos")) Files.setAttribute(destination, "dos:readonly", false);
                } else throw new IOException("Non-regular cache file");
            }
        }
    }
    private static void cleanup(String name) throws Exception {
        Process p = new ProcessBuilder("docker", "rm", "--force", name).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Docker cleanup timed out"); }
        if (p.exitValue() != 0) {
            // A cancelled create may never have registered a container. Require a responsive daemon.
            Process check = new ProcessBuilder("docker", "info", "--format", "{{.ID}}")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!check.waitFor(10, TimeUnit.SECONDS)) { check.destroyForcibly(); throw new IOException("Docker daemon unavailable for cleanup"); }
            if (check.exitValue() != 0) throw new IOException("Docker daemon unavailable for cleanup");
            Process exists = new ProcessBuilder("docker", "inspect", name).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!exists.waitFor(10, TimeUnit.SECONDS)) { exists.destroyForcibly(); throw new IOException("Cleanup verification timed out"); }
            if (exists.exitValue() == 0) throw new IOException("Container still exists after cleanup");
        }
    }
    private static void deleteCache(Path cache) throws Exception {
        if (!Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(cache)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
    @PreDestroy public void shutdown() {
        for (var e : executions.values()) {
            synchronized (e) { if (List.of("QUEUED", "PREPARING", "RUNNING").contains(e.state)) e.state = "CANCEL_REQUESTED"; }
        }
        executor.shutdown();
        try { if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
}
