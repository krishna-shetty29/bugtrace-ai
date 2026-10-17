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
        Integer exitCode, String technicalError, String workspace, String logPath, Instant createdAt, Instant finishedAt,
        String classification, String evidencePath, String stdoutPath, String stderrPath) {}
    private static class Execution {
        final JobRequest job;
        final Instant created = Instant.now();
        String state = "QUEUED";
        Integer exitCode;
        String error;
        Path experiment, log, cache, evidence, stdout, stderr;
        String classification;
        String imageId;
        Instant started;
        boolean oomKilled, stdoutTruncated, stderrTruncated, snapshotVerifiedAfter;
        String installedSourceSha256, experimentContentHash;
        final List<Map<String, Object>> stages = new ArrayList<>();
        String container;
        Process process;
        Instant finished;
        Execution(JobRequest job) { this.job = job; }
        synchronized Result result() { return new Result(job.executionId(), job.snapshotId(), job.operation(), state,
            exitCode, error, experiment == null ? null : experiment.toString(), log == null ? null : log.toString(), created, finished,
            classification, evidence == null ? null : evidence.resolve("execution.json").toString(),
            stdout == null ? null : stdout.toString(), stderr == null ? null : stderr.toString()); }
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
        if(job.generatedTest()!=null) {
            GeneratedTestOverlay.validate(job.generatedTest());
            if(job.operation()!=Operation.RUN_SELECTED_TEST || !GeneratedTestOverlay.CLASS.equals(job.testSelector()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Generated overlay requires its exact selected test");
        }
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
        long deadline = System.nanoTime() + ExecutionDeadline.remainingNanos(e.job.timeoutSeconds(),e.job.notAfter(),Instant.now());
        String terminal = "ERROR";
        try {
            synchronized (e) { if (!cancelled(e)) e.state = "PREPARING"; }
            if (!hash.matches("[0-9a-f]{64}") || !base.startsWith(root) || !base.getFileName().toString().equals("base"))
                throw new IllegalArgumentException("Configure a Step 14 snapshot and hash");
            Path home = base.getParent();
            SnapshotFiles.requireRealPath(home.resolve("experiments"));
            SnapshotFiles.requireRealPath(home.resolve("artifacts"));
            Path artifact = home.resolve("artifacts").resolve(e.job.executionId().toString());
            Files.createDirectory(artifact);
            synchronized (e) {
                e.log = artifact.resolve("worker.log");
                e.evidence = artifact;
                e.stdout = artifact.resolve("stdout.log");
                e.stderr = artifact.resolve("stderr.log");
                e.cache = artifact.resolve("maven-cache");
                e.experiment = home.resolve("experiments").resolve(e.job.executionId().toString());
            }
            Files.createFile(e.log);
            Files.createFile(e.stdout);
            Files.createFile(e.stderr);
            Files.createFile(artifact.resolve("control.stdout.log"));
            Files.createFile(artifact.resolve("control.stderr.log"));
            if (cancelled(e)) { terminal = "CANCELLED"; return; }
            if(System.nanoTime()>=deadline)throw new TimeoutException("Absolute loop deadline expired before execution");
            if (!SnapshotFiles.hash(base).equals(hash)) throw new IllegalArgumentException("Base hash mismatch");
            SnapshotFiles.copy(base, e.experiment);
            if (!SnapshotFiles.hash(e.experiment).equals(hash)) throw new IllegalArgumentException("Experiment copy hash mismatch");
            Path oldReports = e.experiment.resolve("target/surefire-reports");
            if (Files.exists(oldReports, LinkOption.NOFOLLOW_LINKS)) {
                SnapshotFiles.requireRealPath(oldReports);
                deleteCache(oldReports);
            }
            if(e.job.generatedTest()!=null) {
                e.installedSourceSha256=GeneratedTestOverlay.install(e.experiment,e.job.generatedTest());
                e.experimentContentHash=SnapshotFiles.hash(e.experiment);
            }
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
            try {
                e.snapshotVerifiedAfter=SnapshotFiles.hash(base).equals(hash);
                if(!e.snapshotVerifiedAfter){terminal="ERROR";e.error="Immutable base changed during execution";}
            }catch(Exception snapshotError){terminal="ERROR";e.error="Could not verify immutable base after execution";}
            // Persist evidence before publishing a terminal API state.
            synchronized (e) {
                if (cancelled(e) && !terminal.equals("ERROR")) terminal = "CANCELLED";
                e.finished = Instant.now();
            }
            try {
                if (e.evidence != null) {
                    var reports = ExecutionEvidence.empty();
                    try {
                        if (!terminal.equals("ERROR")) reports = ExecutionEvidence.collect(e.experiment, e.evidence);
                    } catch (Exception reportError) {
                        synchronized (e) { e.error = "JUnit evidence collection failed: " + reportError.getMessage(); }
                        // Preserve trusted timeout/cancellation/OOM facts even if the report was left incomplete.
                        if (!terminal.equals("CANCELLED") && !terminal.equals("TIMED_OUT") && !e.oomKilled) terminal = "ERROR";
                    }
                    var outcome = ExecutionEvidence.classify(terminal, e.oomKilled, e.exitCode,
                        e.job.operation() == Operation.COMPILE, e.job.testSelector(),
                        ExecutionEvidence.read(e.stdout), ExecutionEvidence.read(e.stderr), reports);
                    persist(e, terminal, outcome.name(), reports);
                    synchronized (e) { e.classification = outcome.name(); }
                }
            } catch (Exception evidenceError) {
                terminal = "ERROR";
                synchronized (e) { e.error = "Evidence collection failed: " + evidenceError.getMessage(); e.classification = "INFRASTRUCTURE_ERROR"; }
                try { if (e.evidence != null) persist(e, terminal, "INFRASTRUCTURE_ERROR", ExecutionEvidence.empty()); }
                catch (Exception writeError) { synchronized (e) { e.error += "; metadata could not be saved: " + writeError.getMessage(); } }
            }
            synchronized (e) { if (e.classification == null) e.classification = "INFRASTRUCTURE_ERROR"; e.state = terminal; }
            slots.release();
        }
    }
    private int stage(Execution e, List<String> goals, long deadline) throws Exception {
        if (cancelled(e)) throw new CancellationException();
        String name = "bugtrace-" + UUID.randomUUID();
        Instant stageStart = Instant.now();
        synchronized (e) { e.container = name; }
        try {
            int created = command(e, DockerCommands.create(name, image, e.experiment, e.cache, goals), deadline);
            if (created == -1) return -1;
            if (created != 0) throw new IOException("Docker create failed; inspect worker.log");
            if (cancelled(e)) throw new CancellationException();
            synchronized (e) { if (!cancelled(e)) { e.state = "RUNNING"; if (e.started == null) e.started = Instant.now(); } }
            int attached = command(e, List.of("docker", "start", "--attach", name), deadline);
            if (attached == -1 || cancelled(e)) return attached;
            Process inspect = new ProcessBuilder("docker", "inspect", "--format",
                "{{.State.Running}} {{.State.ExitCode}} {{.State.OOMKilled}} {{.Image}}", name)
                .redirectErrorStream(true).start();
            if (!inspect.waitFor(5, TimeUnit.SECONDS)) { inspect.destroyForcibly(); throw new IOException("Container state inspection timed out"); }
            String state = new String(inspect.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8).strip();
            String[] values = state.split(" ");
            if (inspect.exitValue() != 0 || values.length != 4 || !values[0].equals("false"))
                throw new IOException("Could not confirm container completion");
            synchronized (e) {
                e.imageId = values[3];
                e.oomKilled |= values[2].equals("true");
                if (e.oomKilled) e.error = "Container exceeded its memory limit (OOM killed)";
                e.stages.add(Map.of("goals", List.copyOf(goals), "startedAt", stageStart.toString(),
                    "finishedAt", Instant.now().toString(), "exitCode", Integer.parseInt(values[1]), "oomKilled", values[2].equals("true")));
            }
            return Integer.parseInt(values[1]);
        } finally { cleanup(name); synchronized (e) { e.container = null; } }
    }
    private int command(Execution e, List<String> args, long deadline) throws Exception {
        if (System.nanoTime() >= deadline) return -1;
        Process p = new ProcessBuilder(args).start();
        synchronized (e) { e.process = p; }
        boolean repositoryOutput = args.size() > 1 && args.get(1).equals("start");
        Path stdout = repositoryOutput ? e.stdout : e.evidence.resolve("control.stdout.log");
        Path stderr = repositoryOutput ? e.stderr : e.evidence.resolve("control.stderr.log");
        Future<?> outDrain = executor.submit(() -> drain(p.getInputStream(), stdout, e, repositoryOutput, false));
        Future<?> errDrain = executor.submit(() -> drain(p.getErrorStream(), stderr, e, repositoryOutput, true));
        try {
            while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled(e)) { p.destroyForcibly(); throw new CancellationException(); }
                if (System.nanoTime() >= deadline) { p.destroyForcibly(); return -1; }
            }
            outDrain.get(5, TimeUnit.SECONDS);
            errDrain.get(5, TimeUnit.SECONDS);
            return p.exitValue();
        } finally {
            p.destroyForcibly();
            if (p.isAlive()) p.waitFor(2, TimeUnit.SECONDS);
            try { outDrain.get(2, TimeUnit.SECONDS); } catch (Exception ignored) { outDrain.cancel(true); }
            try { errDrain.get(2, TimeUnit.SECONDS); } catch (Exception ignored) { errDrain.cancel(true); }
            synchronized (e) { e.process = null; }
        }
    }
    private static void drain(InputStream stream, Path log, Execution e, boolean repositoryOutput, boolean stderr) {
        try (var input = stream; var out = Files.newOutputStream(log, StandardOpenOption.APPEND)) {
            long written = Files.size(log);
            byte[] bytes = new byte[8192];
            int n;
            while ((n = input.read(bytes)) != -1) {
                int keep = (int) Math.min(n, Math.max(0, 2L * 1024 * 1024 - written));
                out.write(bytes, 0, keep); written += keep;
                synchronized (e) {
                    if (repositoryOutput && keep < n) { if (stderr) e.stderrTruncated = true; else e.stdoutTruncated = true; }
                    long combined = Files.size(e.log);
                    int combinedKeep = (int) Math.min(n, Math.max(0, 2L * 1024 * 1024 - combined));
                    Files.write(e.log, Arrays.copyOf(bytes, combinedKeep), StandardOpenOption.APPEND);
                }
            }
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    private void persist(Execution e, String terminal, String classification, ExecutionEvidence.Reports reports) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executionId", e.job.executionId().toString());
        metadata.put("snapshotId", e.job.snapshotId().toString());
        metadata.put("snapshotContentHash", hash);
        metadata.put("snapshotVerifiedAfter",e.snapshotVerifiedAfter);
        metadata.put("experimentWorkspace",e.experiment==null?null:e.experiment.toString());
        if(e.job.generatedTest()!=null) {
            var overlay=new LinkedHashMap<String,Object>();
            overlay.put("path",e.job.generatedTest().path());overlay.put("className",e.job.generatedTest().className());
            overlay.put("requestedSourceSha256",e.job.generatedTest().sourceSha256());overlay.put("installedSourceSha256",e.installedSourceSha256);
            overlay.put("experimentContentHashBeforeBuild",e.experimentContentHash);
            boolean unchanged=false;
            if(e.installedSourceSha256!=null)try{unchanged=e.installedSourceSha256.equals(ExecutionEvidence.sha256(e.experiment.resolve(e.job.generatedTest().path())));}catch(Exception ignored){}
            overlay.put("unchangedAfterRun",unchanged);metadata.put("generatedTest",overlay);
        }
        metadata.put("operation", e.job.operation().name());
        metadata.put("selectedTest", e.job.testSelector());
        metadata.put("imageIdentifier", image);
        metadata.put("resolvedImageId", e.imageId);
        metadata.put("environment", Map.of("workerJava", System.getProperty("java.version"), "workerOs", System.getProperty("os.name"),
            "containerUser", "1000:1000", "network", "none", "cpuLimit", 1, "memoryBytes", 1073741824L, "pidsLimit", 128));
        metadata.put("createdAt", e.created.toString());
        metadata.put("startedAt", e.started == null ? null : e.started.toString());
        metadata.put("finishedAt", e.finished.toString());
        metadata.put("timeoutSeconds", e.job.timeoutSeconds());
        metadata.put("notAfter",e.job.notAfter()==null?null:e.job.notAfter().toString());
        metadata.put("exitCode", e.exitCode);
        metadata.put("state", terminal);
        metadata.put("classification", classification);
        metadata.put("terminationReason", switch (classification) {
            case "TIMED_OUT" -> "DEADLINE_EXCEEDED";
            case "CANCELLED" -> "USER_CANCELLED";
            case "RESOURCE_LIMIT" -> "DOCKER_OOM_KILLED";
            case "INFRASTRUCTURE_ERROR" -> "WORKER_OR_BUILD_ERROR";
            default -> "PROCESS_EXITED";
        });
        metadata.put("technicalError", e.error);
        metadata.put("stdout", Map.of("file", "stdout.log", "sha256", ExecutionEvidence.sha256(e.stdout), "truncated", e.stdoutTruncated));
        metadata.put("stderr", Map.of("file", "stderr.log", "sha256", ExecutionEvidence.sha256(e.stderr), "truncated", e.stderrTruncated));
        metadata.put("junit", reports);
        metadata.put("stages", List.copyOf(e.stages));
        ExecutionEvidence.write(e.evidence, metadata);
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
