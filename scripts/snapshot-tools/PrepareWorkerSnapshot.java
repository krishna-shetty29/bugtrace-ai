

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Captures Git blobs, never a checkout and never an archive extraction. */
public final class PrepareWorkerSnapshot {
    private PrepareWorkerSnapshot() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("Expected repository, commit, module, workspace, snapshot ID and hash");
        if(!args[1].equals("6c4f8856bb7e4d49e953a8b086d594d2f505838d") || !args[2].matches("cases/case-00[1-6]") || !args[5].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Only the pinned public benchmark release and case modules are supported");
        Path workspace=Path.of(args[3]).toAbsolutePath().normalize();
        UUID snapshot=UUID.fromString(args[4]);
        Path base=workspace.resolve(snapshot.toString()).resolve("base");
        if(Files.exists(base,LinkOption.NOFOLLOW_LINKS)) {
            if(!contentHash(base).equals(args[5]))throw new IllegalArgumentException("Existing local base differs; it will not be overwritten");
        } else {
            Capture result=capture(Path.of(args[0]),args[1],args[2],workspace,snapshot);
            if(!result.commit().equals(args[1]) || !result.contentHash().equals(args[5]))
                throw new IllegalArgumentException("Captured bytes differ from authenticated backend snapshot; do not use this base");
        }
        System.out.println("Verified local snapshot: "+base);
        System.out.println("Content SHA256: "+args[5]);
    }

    public record Capture(String commit, String contentHash, Path base,
                          String buildTool, String sourceDirectory, String testDirectory) {}
    private record Entry(String path, String mode, String object) {}

    public static Capture capture(Path repository, String revision, String module,
                                  Path workspace, UUID investigationId) throws Exception {
        Path repo = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo) || !repo.toRealPath().equals(repo))
            throw new IllegalArgumentException("Repository must be a real local directory, not a linked path");
        String top = text(git(repo, 4096, "rev-parse", "--show-toplevel"));
        if (!Path.of(top).toRealPath().equals(repo))
            throw new IllegalArgumentException("Use the Git repository root, not a subdirectory");
        if (revision == null || !revision.matches("[A-Za-z0-9_./-]{1,200}") || revision.startsWith("-"))
            throw new IllegalArgumentException("Use a commit hash, branch, or tag as revision");
        String commit = text(git(repo, 4096, "rev-parse", "--verify", "--end-of-options", revision + "^{commit}"));
        if (!commit.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new IllegalArgumentException("Revision did not resolve to a commit");
        String prefix = module.equals(".") ? "" : safeRelative(module) + "/";
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, String> portableNames = new HashMap<>();
        byte[] listing = git(repo, 2 * 1024 * 1024, "ls-tree", "-r", "-z", "--full-tree", commit);
        String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(listing)).toString();
        for (String row : decoded.split("\u0000")) {
            if (row.isEmpty()) continue;
            int tab = row.indexOf('\t');
            if (tab < 0) throw new IllegalArgumentException("Malformed Git tree");
            String original = row.substring(tab + 1);
            if (!original.startsWith(prefix)) continue;
            String path = safeRelative(original.substring(prefix.length()));
            String[] metadata = row.substring(0, tab).split(" ");
            if (metadata.length != 3 || !metadata[1].equals("blob") ||
                !(metadata[0].equals("100644") || metadata[0].equals("100755")))
                throw new IllegalArgumentException("Unsupported symlink, submodule, or file mode: " + path);
            if (!metadata[2].matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid Git object");
            // Fold case on every platform: portable snapshots must work on Windows too.
            if (!names.add(path.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Case-colliding paths: " + path);
            String ancestor = "";
            for (String part : path.split("/")) {
                ancestor = ancestor.isEmpty() ? part : ancestor + "/" + part;
                String previous = portableNames.putIfAbsent(ancestor.toLowerCase(Locale.ROOT), ancestor);
                if (previous != null && !previous.equals(ancestor))
                    throw new IllegalArgumentException("Case-colliding path components: " + path);
            }
            entries.add(new Entry(path, metadata[0], metadata[2]));
            if (entries.size() > 1000) throw new IllegalArgumentException("Snapshot exceeds 1000-file limit");
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Module is absent from this revision");
        entries.sort(Comparator.comparing(Entry::path));
        // Detect build tools before creating the workspace.
        if (names.contains("build.gradle") || names.contains("build.gradle.kts"))
            throw new IllegalArgumentException("Gradle detected; unsupported by this initial Maven importer");
        Entry pom = entries.stream().filter(e -> e.path.equals("pom.xml")).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No pom.xml at selected module root"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(git(repo, 5 * 1024 * 1024, "cat-file", "blob", pom.object)));
        for (String tag : List.of("modules", "sourceDirectory", "testSourceDirectory"))
            if (doc.getElementsByTagName(tag).getLength() > 0)
                throw new IllegalArgumentException("Unsupported Maven configuration: " + tag);
        for (String tag : List.of("artifactId")) {
            var nodes = doc.getElementsByTagName(tag);
            for (int n = 0; n < nodes.getLength(); n++) {
                String value = nodes.item(n).getTextContent().strip();
                if (value.equals("kotlin-maven-plugin") || value.equals("build-helper-maven-plugin"))
                    throw new IllegalArgumentException("Custom source layout plugin unsupported: " + value);
            }
        }
        if (entries.stream().noneMatch(e -> e.path.startsWith("src/main/java/")))
            throw new IllegalArgumentException("Only conventional Maven Java sources are supported");
        String tests = entries.stream().anyMatch(e -> e.path.startsWith("src/test/java/")) ? "src/test/java" : "";

        Path root = workspace.toAbsolutePath().normalize();
        Path existingAncestor = root;
        while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) existingAncestor = existingAncestor.getParent();
        requireRealAncestors(existingAncestor);
        Files.createDirectories(root);
        requireRealAncestors(root);
        Path home = root.resolve(investigationId.toString());
        Files.createDirectory(home); // Reject overwrite, retry, and pre-planted paths.
        Path staging = home.resolve(".staging");
        boolean published = false;
        try {
            Files.createDirectory(staging);
            long total = 0;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            for (Entry entry : entries) {
                if (System.nanoTime() > deadline) throw new IOException("Snapshot import exceeded two minutes");
                Path destination = staging.resolve(entry.path).normalize();
                if (!destination.startsWith(staging)) throw new IllegalArgumentException("Path escapes workspace");
                byte[] bytes = git(repo, 5 * 1024 * 1024, "cat-file", "blob", entry.object);
                total += bytes.length;
                if (total > 64L * 1024 * 1024) throw new IllegalArgumentException("Snapshot exceeds 64 MiB limit");
                if (new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.UTF_8)
                    .startsWith("version https://git-lfs.github.com/spec/v1"))
                    throw new IllegalArgumentException("Git LFS content unsupported: " + entry.path);
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
                if (entry.mode.equals("100755") && Files.getFileStore(destination).supportsFileAttributeView("posix")) {
                    var permissions = Files.getPosixFilePermissions(destination);
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(destination, permissions);
                }
            }
            String hash = contentHash(staging);
            for (String folder : List.of("experiments", "patches", "artifacts"))
                Files.createDirectory(home.resolve(folder));
            Path base = home.resolve("base");
            Files.move(staging, base, StandardCopyOption.ATOMIC_MOVE);
            // Read-only files are an accident guard. Process isolation is still required.
            try (var paths = Files.walk(base)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    if (Files.getFileStore(file).supportsFileAttributeView("dos"))
                        Files.setAttribute(file, "dos:readonly", true);
                    else if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                        var permissions = Files.getPosixFilePermissions(file);
                        permissions.remove(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                        permissions.remove(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
                        permissions.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
                        Files.setPosixFilePermissions(file, permissions);
                    }
                }
            }
            published = true;
            return new Capture(commit, hash, base, "MAVEN", "src/main/java", tests);
        } finally {
            if (!published) deleteOwnedTree(home);
        }
    }

    public static String safeRelative(String path) {
        if (path == null || path.isEmpty() || path.length() > 200 || path.startsWith("/"))
            throw new IllegalArgumentException("Invalid relative path");
        for (String part : path.split("/", -1)) {
            if (!part.matches("[A-Za-z0-9_.-]+") || part.equals(".") || part.equals("..") || part.endsWith("."))
                throw new IllegalArgumentException("Unsafe or unsupported file path: " + path);
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(stem)
                || part.equalsIgnoreCase(".git") || (part.toLowerCase(Locale.ROOT).startsWith(".env") && !part.equals(".env.example")))
                throw new IllegalArgumentException("Forbidden file path: " + path);
        }
        return path;
    }

    private static void requireRealAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))
                throw new IOException("Linked workspace paths are forbidden");
    }

    public static String contentHash(Path base) throws Exception {
        requireRealAncestors(base.toAbsolutePath().normalize());
        var digest = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (var paths = Files.walk(base)) {
            files = paths.sorted(Comparator.comparing(p -> base.relativize(p).toString().replace('\\', '/'))).toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !file.toRealPath().equals(file.toAbsolutePath().normalize()))
                throw new IOException("Snapshot contains a linked path");
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular snapshot entry");
            byte[] name = safeRelative(base.relativize(file).toString().replace('\\', '/')).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
            digest.update(name);
            long size = Files.size(file);
            if (size > 5L * 1024 * 1024) throw new IOException("Snapshot file exceeds size limit");
            digest.update(ByteBuffer.allocate(8).putLong(size).array());
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8).strip(); }
    private static byte[] git(Path repo, int limit, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "--no-replace-objects", "-C", repo.toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        // Do not let inherited Git variables point at a different repository or index.
        builder.environment().keySet().removeIf(k -> k.startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_NO_LAZY_FETCH", "1");
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<byte[]> output = reader.submit(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(limit + 1);
                if (bytes.length > limit) {
                    process.destroyForcibly();
                    throw new IOException("Git output exceeds configured limit");
                }
                return bytes;
            }
        });
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Git command timed out");
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IllegalArgumentException("Git command failed: check repository and committed revision");
            return bytes;
        } finally {
            process.destroyForcibly();
            reader.shutdownNow();
        }
    }

    public static void deleteOwnedTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(item) && Files.getFileStore(item).supportsFileAttributeView("dos"))
                    Files.setAttribute(item, "dos:readonly", false);
                Files.delete(item);
            }
        }
    }
}
