package com.bugtrace.backend;

import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SnapshotImportService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Path allowedRepository;
    private final Path workspace;

    public SnapshotImportService(JdbcTemplate jdbc, TransactionTemplate transactions,
        @Value("${bugtrace.repository-root}") String allowedRepository,
        @Value("${bugtrace.workspace-root}") String workspace) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.allowedRepository = Path.of(allowedRepository).toAbsolutePath().normalize();
        this.workspace = Path.of(workspace).toAbsolutePath().normalize();
    }

    public synchronized GitSnapshot.Capture importSnapshot(UUID id, String repositoryPath,
        String revision, String module) {
        Path repository = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!repository.equals(allowedRepository))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repository is outside the configured allowlist");
        Path[] ownedHome = new Path[1];
        try {
            return transactions.execute(status -> {
                var rows = jdbc.query("""
                    SELECT s.id, s.commit_sha, s.source_location
                    FROM investigations i JOIN repository_snapshots s ON s.id = i.snapshot_id
                    WHERE i.id = ? FOR UPDATE OF s
                    """, (rs, n) -> new Object[] {rs.getObject("id", UUID.class),
                        rs.getString("commit_sha"), rs.getString("source_location")}, id);
                if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Investigation not found");
                Object[] row = rows.getFirst();
                if (row[2] != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Base snapshot already captured; it cannot be overwritten");
                try {
                    var capture = GitSnapshot.capture(repository, revision, module, workspace, id);
                    ownedHome[0] = capture.base().getParent();
                    if (!capture.commit().equalsIgnoreCase((String) row[1]))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolved commit differs from investigation snapshot; create an investigation with the intended commit");
                    jdbc.update("""
                        UPDATE repository_snapshots SET repository_url = ?, source_location = ?,
                        content_hash = ?, build_tool = ?, source_directory = ?, test_directory = ?,
                        module_path = ?, imported_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, repository.toString(), capture.base().toString(), capture.contentHash(),
                        capture.buildTool(), capture.sourceDirectory(), capture.testDirectory(), module, row[0]);
                    return capture;
                } catch (ResponseStatusException exception) { throw exception; }
                catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
                } catch (Exception exception) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Snapshot capture failed; check local Git and workspace access", exception);
                }
            });
        } catch (RuntimeException exception) {
            if (ownedHome[0] != null) {
                try { GitSnapshot.deleteOwnedTree(ownedHome[0]); }
                catch (Exception cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        }
    }

    public synchronized Map<String, Object> verifySnapshot(UUID id) {
        var rows = jdbc.query("""
            SELECT s.source_location, s.content_hash FROM investigations i
            JOIN repository_snapshots s ON s.id = i.snapshot_id WHERE i.id = ?
            """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Investigation not found");
        String[] row = rows.getFirst();
        if (row[0] == null || row[1] == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Snapshot has not been imported");
        Path base = Path.of(row[0]).toAbsolutePath().normalize();
        if (!base.equals(workspace.resolve(id.toString()).resolve("base")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Snapshot location does not match workspace");
        try {
            String actual = GitSnapshot.contentHash(base);
            if (!actual.equals(row[1]))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Base snapshot content changed; do not use it for experiments");
            return Map.of("verified", true, "contentHash", actual);
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Base snapshot is missing or contains unsafe paths", exception);
        }
    }
}
