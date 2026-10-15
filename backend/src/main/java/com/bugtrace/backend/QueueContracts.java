package com.bugtrace.backend;

import java.util.*;
import jakarta.validation.constraints.*;

public final class QueueContracts {
    private QueueContracts() {}
    public enum Operation { COMPILE, RUN_SELECTED_TEST, RUN_TEST_SUITE }
    public record CreateJob(@NotNull Operation operation, @Size(max=200) String testSelector,
                            @Min(1) @Max(600) int timeoutSeconds) {}
    public record Claim(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String workerId, @NotNull UUID snapshotId) {}
    public record Heartbeat(@NotNull UUID leaseToken, @NotBlank String workerId,
                            @NotNull Map<String,Object> progress) {}
    public record Complete(@NotNull UUID leaseToken, @NotBlank String workerId,
                           @NotNull Map<String,Object> metadata,
                           @NotNull @Size(max=2097152) String stdout,
                           @NotNull @Size(max=2097152) String stderr) {}
    public static void validate(CreateJob job) {
        if (job.operation() == Operation.RUN_SELECTED_TEST) {
            if (job.testSelector() == null || !job.testSelector().matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))
                throw new IllegalArgumentException("Use one Java test class name");
        } else if (job.testSelector() != null)
            throw new IllegalArgumentException("testSelector is allowed only for RUN_SELECTED_TEST");
    }
    public static void validateResult(Map<String,Object> metadata, UUID attempt, UUID snapshot,
        String operation, String selector, String expectedHash) {
        if (!attempt.toString().equals(metadata.get("executionId")) || !snapshot.toString().equals(metadata.get("snapshotId"))
            || !operation.equals(metadata.get("operation")) || !Objects.equals(selector, metadata.get("selectedTest"))
            || !expectedHash.equals(metadata.get("snapshotContentHash")))
            throw new IllegalArgumentException("Result does not match the claimed attempt and snapshot");
        String state = String.valueOf(metadata.get("state"));
        String classification = String.valueOf(metadata.get("classification"));
        boolean valid = switch (state) {
            case "SUCCEEDED" -> classification.equals("PASSED");
            case "FAILED" -> Set.of("ASSERTION_FAILED","COMPILATION_FAILED","RESOURCE_LIMIT","INFRASTRUCTURE_ERROR").contains(classification);
            case "ERROR" -> classification.equals("INFRASTRUCTURE_ERROR");
            case "TIMED_OUT" -> classification.equals("TIMED_OUT");
            case "CANCELLED" -> classification.equals("CANCELLED");
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Invalid terminal state or classification");
    }
}
