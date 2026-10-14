package com.bugtrace.worker;

import java.nio.file.Path;
import java.util.*;
import com.bugtrace.worker.WorkerController.Operation;

public final class MavenCommands {
    private MavenCommands() {}

    public static List<String> arguments(Operation operation, String selector, Path pom) {
        List<String> args = new ArrayList<>(List.of("-B", "-ntp", "-f", pom.toString()));
        if (operation == Operation.RUN_SELECTED_TEST) {
            if (selector == null || selector.length() > 200 ||
                !selector.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))
                throw new IllegalArgumentException("Use one simple or fully qualified Java test class name");
            args.add("-Dtest=" + selector);
            args.add("-DfailIfNoTests=true");
            args.add("-Dsurefire.failIfNoSpecifiedTests=true");
            args.add("test");
        } else {
            if (selector != null && !selector.isBlank())
                throw new IllegalArgumentException("testSelector is allowed only for RUN_SELECTED_TEST");
            switch (operation) {
                case COMPILE -> args.add("compile");
                case RUN_TEST_SUITE -> args.add("test");
                default -> throw new IllegalArgumentException("Cancellation does not execute a Maven command");
            }
        }
        return List.copyOf(args);
    }

    public static List<String> windowsCommand(Operation operation, String selector, Path pom) {
        // This initial Windows prototype uses cmd only to invoke our trusted wrapper.
        // Restrict paths to the simple layout used in this tutorial: no spaces/metacharacters.
        if (!pom.toString().matches("[A-Za-z]:[\\\\/A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("This Windows worker requires a path without spaces or shell metacharacters");
        var command = new ArrayList<>(List.of("cmd.exe", "/d", "/c", "mvnw.cmd"));
        command.addAll(arguments(operation, selector, pom));
        return command;
    }
}
