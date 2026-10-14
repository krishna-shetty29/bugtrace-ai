package com.bugtrace.worker;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class SnapshotFiles {
    private SnapshotFiles() {}

    public static void requireRealPath(Path path) throws Exception {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))
                throw new IllegalArgumentException("Linked workspace paths are forbidden");
        }
    }

    public static String hash(Path base) throws Exception {
        requireRealPath(base);
        var digest = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (var paths = Files.walk(base)) {
            files = paths.sorted(Comparator.comparing(p -> base.relativize(p).toString().replace('\\', '/'))).toList();
        }
        long total = 0;
        int count = 0;
        for (Path file : files) {
            requireRealPath(file);
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Snapshot contains non-regular files");
            String relative = base.relativize(file).toString().replace('\\', '/');
            if (relative.length() > 200 || !relative.matches("[A-Za-z0-9_./-]+"))
                throw new IllegalArgumentException("Unsupported snapshot filename");
            byte[] name = relative.getBytes(StandardCharsets.UTF_8);
            long size = Files.size(file);
            total += size;
            if (++count > 1000 || size > 5L * 1024 * 1024 || total > 64L * 1024 * 1024)
                throw new IllegalArgumentException("Snapshot exceeds size limits");
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
            digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(size).array());
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void copy(Path base, Path experiment) throws Exception {
        Files.createDirectory(experiment);
        try (var paths = Files.walk(base)) {
            for (Path source : paths.toList()) {
                requireRealPath(source);
                Path destination = experiment.resolve(base.relativize(source)).normalize();
                if (!destination.startsWith(experiment)) throw new IllegalArgumentException("Path escape");
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else {
                    Files.copy(source, destination);
                    if (Files.getFileStore(destination).supportsFileAttributeView("dos"))
                        Files.setAttribute(destination, "dos:readonly", false);
                    destination.toFile().setWritable(true, true);
                }
            }
        }
    }
}
