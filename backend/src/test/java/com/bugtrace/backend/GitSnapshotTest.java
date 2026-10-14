package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GitSnapshotTest {
    @TempDir Path temp;

    @AfterEach void allowTemporaryCleanup() throws Exception {
        try (var paths = Files.walk(temp)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.getFileStore(path).supportsFileAttributeView("dos"))
                    Files.setAttribute(path, "dos:readonly", false);
            }
        }
    }

    private Path repo() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        git(repo, "init");
        git(repo, "config", "user.name", "Snapshot Test");
        git(repo, "config", "user.email", "snapshot-test@example.invalid");
        Files.writeString(repo.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/Main.java"), "class Main {}\n");
        Files.createDirectories(repo.resolve("src/test/java"));
        Files.writeString(repo.resolve("src/test/java/MainTest.java"), "class MainTest {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "fixture");
        return repo;
    }

    @Test void capturesCommittedBytesAndStableHashWithoutHistory() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("src/main/java/Main.java"), "uncommitted change");
        Path workspace = temp.resolve("workspace");
        var first = GitSnapshot.capture(repo, "HEAD", ".", workspace, UUID.randomUUID());
        var second = GitSnapshot.capture(repo, "HEAD", ".", workspace, UUID.randomUUID());
        assertEquals("class Main {}\n", Files.readString(first.base().resolve("src/main/java/Main.java")));
        assertEquals(first.contentHash(), second.contentHash());
        assertFalse(Files.exists(first.base().resolve(".git")));
        assertEquals("MAVEN", first.buildTool());
        assertEquals("src/test/java", first.testDirectory());
        assertEquals(first.contentHash(), GitSnapshot.contentHash(first.base()));
        assertThrows(Exception.class, () -> GitSnapshot.capture(repo, "HEAD", ".", workspace,
            UUID.fromString(first.base().getParent().getFileName().toString())));
    }

    @Test void rejectsUnsafePortablePaths() {
        for (String path : List.of("../escape", "/absolute", "C:/escape", "a\\b", "a//b", "NUL.txt", "file.", ".git/config", ".env", ".env.production"))
            assertThrows(IllegalArgumentException.class, () -> GitSnapshot.safeRelative(path), path);
        assertEquals(".mvn/wrapper/maven-wrapper.properties", GitSnapshot.safeRelative(".mvn/wrapper/maven-wrapper.properties"));
    }

    @Test void rejectsCommittedSymlinkWithoutExtractingIt() throws Exception {
        Path repo = repo();
        // Change the index mode directly: works on Windows without symlink privileges.
        String blob = git(repo, "rev-parse", "HEAD:src/main/java/Main.java");
        git(repo, "update-index", "--add", "--cacheinfo", "120000," + blob + ",escape-link");
        git(repo, "commit", "-m", "symlink");
        assertThrows(IllegalArgumentException.class, () -> GitSnapshot.capture(repo, "HEAD", ".", temp.resolve("workspace"), UUID.randomUUID()));
    }

    @Test void rejectsMissingRevisionAndGradleLayout() throws Exception {
        Path repo = repo();
        assertThrows(IllegalArgumentException.class, () -> GitSnapshot.capture(repo, "missing-tag", ".", temp.resolve("workspace"), UUID.randomUUID()));
        Files.writeString(repo.resolve("build.gradle"), "plugins {}");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "unsupported build");
        assertThrows(IllegalArgumentException.class, () -> GitSnapshot.capture(repo, "HEAD", ".", temp.resolve("workspace"), UUID.randomUUID()));
    }

    @Test void selectsModuleAndDetectsTampering() throws Exception {
        Path repo = repo();
        Files.createDirectories(repo.resolve("module/src/main/java"));
        Files.writeString(repo.resolve("module/pom.xml"), "<project/>");
        Files.writeString(repo.resolve("module/src/main/java/App.java"), "class App {}\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "module");
        var capture = GitSnapshot.capture(repo, "HEAD", "module", temp.resolve("workspace"), UUID.randomUUID());
        assertTrue(Files.exists(capture.base().resolve("pom.xml")));
        assertFalse(Files.exists(capture.base().resolve("module")));
        Path source = capture.base().resolve("src/main/java/App.java");
        if (Files.getFileStore(source).supportsFileAttributeView("dos")) Files.setAttribute(source, "dos:readonly", false);
        else source.toFile().setWritable(true);
        Files.writeString(source, "changed");
        assertNotEquals(capture.contentHash(), GitSnapshot.contentHash(capture.base()));
    }

    @Test void rejectsSubmodulesAndCustomMavenLayout() throws Exception {
        Path repo = repo();
        String commit = git(repo, "rev-parse", "HEAD");
        git(repo, "update-index", "--add", "--cacheinfo", "160000," + commit + ",nested-repo");
        git(repo, "commit", "-m", "submodule");
        assertThrows(IllegalArgumentException.class, () -> GitSnapshot.capture(repo, "HEAD", ".", temp.resolve("workspace"), UUID.randomUUID()));
        git(repo, "rm", "--cached", "nested-repo");
        Files.writeString(repo.resolve("pom.xml"), "<project><modules><module>child</module></modules></project>");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "custom layout");
        assertThrows(IllegalArgumentException.class, () -> GitSnapshot.capture(repo, "HEAD", ".", temp.resolve("workspace"), UUID.randomUUID()));
    }

    private String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Path log = temp.resolve(UUID.randomUUID() + ".log");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("Test Git command timed out"); }
        String output = Files.readString(log).strip();
        if (p.exitValue() != 0) throw new IllegalStateException(output);
        return output;
    }
}
