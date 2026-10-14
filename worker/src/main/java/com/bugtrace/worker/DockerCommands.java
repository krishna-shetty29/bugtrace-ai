package com.bugtrace.worker;

import java.nio.file.Path;
import java.util.*;

public final class DockerCommands {
    private DockerCommands() {}
    public static List<String> create(String name, String image, Path experiment, Path cache, List<String> goals) {
        if (!name.matches("bugtrace-[0-9a-f-]+")) throw new IllegalArgumentException("Invalid container name");
        if (!image.matches("maven@sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Register a pulled Maven image digest");
        var args = new ArrayList<>(List.of(
            "docker", "create", "--name", name, "--label", "bugtrace.execution=true",
            "--init", "--user", "1000:1000", "--cpus", "1", "--memory", "1g", "--memory-swap", "1g",
            "--pids-limit", "128", "--ulimit", "nofile=1024:1024", "--ulimit", "core=0",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges=true", "--read-only", "--network", "none", "--pull", "never",
            "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,uid=1000,gid=1000",
            "--tmpfs", "/home/runner:rw,nosuid,nodev,size=64m,uid=1000,gid=1000",
            "--mount", "type=bind,source=" + mount(experiment) + ",target=/work",
            "--mount", "type=bind,source=" + mount(cache) + ",target=/m2",
            "--workdir", "/work", "--env", "HOME=/home/runner",
            "--env", "JAVA_TOOL_OPTIONS=-Xmx256m -Duser.home=/home/runner",
            "--entrypoint", "/usr/share/maven/bin/mvn", image, "-B", "-ntp", "-o", "-Dmaven.repo.local=/m2"));
        args.addAll(goals);
        return List.copyOf(args);
    }
    private static String mount(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.contains(",") || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Unsupported mount path");
        return value;
    }
}
