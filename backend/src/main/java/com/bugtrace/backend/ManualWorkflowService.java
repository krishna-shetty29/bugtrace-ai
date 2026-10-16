package com.bugtrace.backend;

import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManualWorkflowService {
    private final JdbcTemplate jdbc;
    private final SnapshotImportService imports;
    private final InvestigationService investigations;
    private final String snapshotId;
    private final String fixtureCommit;
    private final Path repository;

    public ManualWorkflowService(JdbcTemplate jdbc, SnapshotImportService imports,
        InvestigationService investigations,
        @Value("${bugtrace.workflow.snapshot-id:NOT_CONFIGURED}") String snapshotId,
        @Value("${bugtrace.workflow.fixture-commit:f1bd5e38b7c98e70ab19be828bc17852ffd81839}") String fixtureCommit,
        @Value("${bugtrace.repository-root}") String repository) {
        this.jdbc=jdbc; this.imports=imports; this.investigations=investigations;
        this.snapshotId=snapshotId; this.fixtureCommit=fixtureCommit;
        this.repository=Path.of(repository).toAbsolutePath().normalize();
    }

    public record Fixture(UUID snapshotId, UUID projectId, UUID importingInvestigationId, String commitSha,
        String modulePath, String contentHash) {}

    public List<Fixture> fixtures() {
        UUID id=configuredSnapshot();
        var rows=jdbc.queryForList("SELECT * FROM repository_snapshots WHERE id=?",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Configured fixture snapshot does not exist");
        var row=rows.getFirst();
        checkFixture(row);
        return List.of(new Fixture(id,(UUID)row.get("project_id"),owner(row),row.get("commit_sha").toString(),
            row.get("module_path").toString(),row.get("content_hash").toString()));
    }

    @Transactional
    public InvestigationService.Investigation create(UUID id,String title,String bug,String expected,String observed) {
        if (!configuredSnapshot().equals(id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select the configured fixture snapshot");
        var rows=jdbc.queryForList("SELECT * FROM repository_snapshots WHERE id=? FOR SHARE",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot not found");
        var row=rows.getFirst(); checkFixture(row);
        UUID owner=owner(row);
        Integer matches=jdbc.queryForObject("SELECT COUNT(*) FROM investigations WHERE id=? AND snapshot_id=?",Integer.class,owner,id);
        if (matches==null || matches!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Snapshot owner does not match");
        imports.verifySnapshot(owner);
        UUID report=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior,workflow_state)
            VALUES (?,?,?,?,?,?,?,'CREATED')
            """,report,row.get("project_id"),id,title.strip(),bug.strip(),expected.strip(),observed.strip());
        return investigations.getInvestigation(report);
    }

    private UUID configuredSnapshot() {
        try { return UUID.fromString(snapshotId); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Configure bugtrace.workflow.snapshot-id to the same imported snapshot as the worker"); }
    }
    private UUID owner(Map<String,Object> row) {
        try { return UUID.fromString(Path.of(row.get("source_location").toString()).getParent().getFileName().toString()); }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid snapshot workspace"); }
    }
    private void checkFixture(Map<String,Object> row) {
        if (row.get("source_location")==null || row.get("content_hash")==null ||
            !"fixtures/checkout-timeout".equals(row.get("module_path")) ||
            !fixtureCommit.equalsIgnoreCase(String.valueOf(row.get("commit_sha"))) ||
            !"MAVEN".equalsIgnoreCase(String.valueOf(row.get("build_tool"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Select an imported Maven checkout fixture at the configured buggy commit");
        if (!repository.equals(Path.of(row.get("repository_url").toString()).toAbsolutePath().normalize()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Fixture repository differs from the configured repository");
    }
}
