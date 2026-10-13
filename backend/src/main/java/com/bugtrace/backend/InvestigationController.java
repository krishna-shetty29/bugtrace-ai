package com.bugtrace.backend;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class InvestigationController {

    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    public record CreateProjectRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 10000) String description
    ) {}

    public record CreateInvestigationRequest(
        @NotNull UUID projectId,
        @NotBlank @Size(max = 2000) String repositoryUrl,
        @NotBlank
        @Pattern(
            regexp = "(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})",
            message = "must be a full 40- or 64-character Git commit hash"
        )
        String commitSha,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 20000) String bugDescription,
        @NotBlank @Size(max = 20000) String expectedBehavior,
        @NotBlank @Size(max = 20000) String observedBehavior
    ) {}

    @PostMapping("/projects")
    public ResponseEntity<InvestigationService.Project> createProject(
        @Valid @RequestBody CreateProjectRequest request
    ) {
        return ResponseEntity.status(201).body(
            service.createProject(
                request.name(),
                request.description()
            )
        );
    }

    @GetMapping("/projects")
    public List<InvestigationService.Project> listProjects() {
        return service.listProjects();
    }

    @PostMapping("/investigations")
    public ResponseEntity<InvestigationService.Investigation>
        createInvestigation(
            @Valid @RequestBody CreateInvestigationRequest request
        ) {
        var investigation = service.createInvestigation(
            request.projectId(),
            request.repositoryUrl(),
            request.commitSha(),
            request.title(),
            request.bugDescription(),
            request.expectedBehavior(),
            request.observedBehavior()
        );

        return ResponseEntity
            .created(URI.create(
                "/api/investigations/" + investigation.id()
            ))
            .body(investigation);
    }

    @GetMapping("/investigations")
    public List<InvestigationService.Investigation> listInvestigations() {
        return service.listInvestigations();
    }

    @GetMapping("/investigations/{id}")
    public InvestigationService.Investigation getInvestigation(
        @PathVariable("id") UUID id
    ) {
        return service.getInvestigation(id);
    }
}