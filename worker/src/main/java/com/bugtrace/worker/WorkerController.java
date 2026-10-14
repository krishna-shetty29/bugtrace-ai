package com.bugtrace.worker;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class WorkerController {
    private final WorkerService service;
    public WorkerController(WorkerService service) { this.service = service; }

    public enum Operation { COMPILE, RUN_SELECTED_TEST, RUN_TEST_SUITE, CANCEL_EXECUTION }

    public record JobRequest(
        @NotNull UUID executionId,
        @NotNull UUID snapshotId,
        @NotNull Operation operation,
        @Size(max = 200) String testSelector,
        @Min(1) @Max(600) int timeoutSeconds
    ) {}

    @PostMapping
    public ResponseEntity<WorkerService.Result> submit(@Valid @RequestBody JobRequest request) {
        return ResponseEntity.accepted().body(service.submit(request));
    }

    @GetMapping("/{id}")
    public WorkerService.Result result(@PathVariable("id") UUID id) { return service.result(id); }
}
