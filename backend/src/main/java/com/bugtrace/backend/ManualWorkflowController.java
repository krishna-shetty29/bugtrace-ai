package com.bugtrace.backend;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manual-workflow")
public class ManualWorkflowController {
    private final ManualWorkflowService service;
    public ManualWorkflowController(ManualWorkflowService service) { this.service=service; }
    public record Report(@NotNull UUID snapshotId,
        @NotBlank @Size(max=300) String title,
        @NotBlank @Size(max=20000) String bugDescription,
        @NotBlank @Size(max=20000) String expectedBehavior,
        @NotBlank @Size(max=20000) String observedBehavior) {}
    @GetMapping("/fixtures") public List<ManualWorkflowService.Fixture> fixtures() { return service.fixtures(); }
    @PostMapping("/investigations")
    public ResponseEntity<InvestigationService.Investigation> create(@Valid @RequestBody Report report) {
        var created=service.create(report.snapshotId(),report.title(),report.bugDescription(),report.expectedBehavior(),report.observedBehavior());
        return ResponseEntity.created(URI.create("/api/investigations/"+created.id())).body(created);
    }
}
