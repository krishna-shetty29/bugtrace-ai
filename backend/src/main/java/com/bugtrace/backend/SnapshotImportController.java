package com.bugtrace.backend;

import java.util.UUID;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations")
public class SnapshotImportController {
    private final SnapshotImportService service;
    public SnapshotImportController(SnapshotImportService service) { this.service = service; }

    public record ImportRequest(
        @NotBlank @Size(max = 1000) String repositoryPath,
        @NotBlank @Size(max = 200) String revision,
        @NotBlank @Size(max = 200) String modulePath
    ) {}

    @PostMapping("/{id}/snapshot/import")
    public GitSnapshot.Capture importSnapshot(@PathVariable("id") UUID id,
        @Valid @RequestBody ImportRequest request) {
        return service.importSnapshot(id, request.repositoryPath(), request.revision(), request.modulePath());
    }

    @GetMapping("/{id}/snapshot/verify")
    public Map<String, Object> verifySnapshot(@PathVariable("id") UUID id) {
        return service.verifySnapshot(id);
    }
}
