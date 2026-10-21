package com.bugtrace.backend;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvestigationService {

    private final JdbcTemplate jdbc;

    public InvestigationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Project(
        UUID id,
        String name,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public record Investigation(
        UUID id,
        UUID projectId,
        UUID snapshotId,
        String title,
        String bugDescription,
        String expectedBehavior,
        String observedBehavior,
        String workflowState,
        String outcome,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    private static final RowMapper<Project> PROJECT_MAPPER =
        (rs, rowNum) -> new Project(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );

    private static final RowMapper<Investigation> INVESTIGATION_MAPPER =
        (rs, rowNum) -> new Investigation(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("snapshot_id", UUID.class),
            rs.getString("title"),
            rs.getString("bug_description"),
            rs.getString("expected_behavior"),
            rs.getString("observed_behavior"),
            rs.getString("workflow_state"),
            rs.getString("outcome"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );

    @Transactional
    public Project createProject(String name, String description) {
        UUID id = UUID.randomUUID();

        return jdbc.queryForObject("""
            INSERT INTO projects (id, name, description)
            VALUES (?, ?, ?)
            RETURNING *
            """,
            PROJECT_MAPPER, id, name.strip(), description
        );
    }

    public List<Project> listProjects() {
        if(TeamAccessContext.project()!=null)return jdbc.query("SELECT * FROM projects WHERE id=? ORDER BY created_at DESC,id",PROJECT_MAPPER,TeamAccessContext.project());
        return jdbc.query("""
            SELECT *
            FROM projects
            ORDER BY created_at DESC, id
            """, PROJECT_MAPPER);
    }

    @Transactional
    public Investigation createInvestigation(
        UUID projectId,
        String repositoryUrl,
        String commitSha,
        String title,
        String bugDescription,
        String expectedBehavior,
        String observedBehavior
    ) {
        TeamAccessContext.requireProject(projectId);
        List<UUID> projects = jdbc.query(
            "SELECT id FROM projects WHERE id = ?",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            projectId
        );

        if (projects.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Project not found"
            );
        }

        UUID snapshotId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO repository_snapshots
                (id, project_id, repository_url, commit_sha)
            VALUES (?, ?, ?, ?)
            """,
            snapshotId,
            projectId,
            repositoryUrl.strip(),
            commitSha.strip()
        );

        UUID investigationId = UUID.randomUUID();

        var created=jdbc.queryForObject("""
            INSERT INTO investigations (
                id,
                project_id,
                snapshot_id,
                title,
                bug_description,
                expected_behavior,
                observed_behavior,
                workflow_state
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATED')
            RETURNING *
            """,
            INVESTIGATION_MAPPER,
            investigationId,
            projectId,
            snapshotId,
            title.strip(),
            bugDescription.strip(),
            expectedBehavior.strip(),
            observedBehavior.strip()
        );
        TeamAccessContext.record(jdbc,"INVESTIGATION_SUBMITTED",investigationId);return created;
    }

    public List<Investigation> listInvestigations() {
        if(TeamAccessContext.project()!=null)return jdbc.query("SELECT * FROM investigations WHERE project_id=? ORDER BY created_at DESC,id",INVESTIGATION_MAPPER,TeamAccessContext.project());
        return jdbc.query("""
            SELECT *
            FROM investigations
            ORDER BY created_at DESC, id
            """, INVESTIGATION_MAPPER);
    }

    public Investigation getInvestigation(UUID id) {
        List<Investigation> results = jdbc.query(
            "SELECT * FROM investigations WHERE id = ?",
            INVESTIGATION_MAPPER,
            id
        );

        if (results.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Investigation not found"
            );
        }

        TeamAccessContext.requireProject(results.getFirst().projectId());return results.getFirst();
    }
}