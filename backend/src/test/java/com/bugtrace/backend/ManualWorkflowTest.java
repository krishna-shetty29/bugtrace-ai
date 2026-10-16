package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ManualWorkflowTest {
    @TempDir Path workspace;
    final String commit="f1bd5e38b7c98e70ab19be828bc17852ffd81839";
    static class Database extends JdbcTemplate {
        Map<String,Object> row; int count=1; Object[] inserted; String[] source;
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) { return List.of(row); }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) { return type.cast(count); }
        @Override public int update(String sql,Object... args) { inserted=args; return 1; }
        @Override @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) { return Collections.singletonList((T)source); }
    }
    static class Imports extends SnapshotImportService {
        UUID verified;
        Imports(Path workspace) { super(new Database(),new TransactionTemplate(),workspace.toString(),workspace.toString()); }
        @Override public synchronized Map<String,Object> verifySnapshot(UUID id) { verified=id; return Map.of("verified",true); }
    }
    static class Reports extends InvestigationService {
        final UUID project,snapshot;
        Reports(UUID project,UUID snapshot) { super(new Database());this.project=project;this.snapshot=snapshot; }
        @Override public Investigation getInvestigation(UUID id) { return new Investigation(id,project,snapshot,"Report","bug","one","two","CREATED",null,null,null); }
    }
    @Test void createsAnotherReportWithoutChangingTheWorkerSnapshot() {
        var jdbc=new Database(); var imports=new Imports(workspace);
        UUID snapshot=UUID.randomUUID(),project=UUID.randomUUID(),owner=UUID.randomUUID();
        jdbc.row=row(snapshot,project,owner,workspace);
        var service=new ManualWorkflowService(jdbc,imports,new Reports(project,snapshot),snapshot.toString(),commit,workspace.toString());
        assertEquals(snapshot,service.fixtures().getFirst().snapshotId());
        var created=service.create(snapshot,"Report","bug","one","two");
        assertEquals(snapshot,created.snapshotId()); assertNotEquals(owner,created.id());
        assertEquals(owner,imports.verified);
        assertEquals(project,jdbc.inserted[1]); assertEquals(snapshot,jdbc.inserted[2]);
    }
    @Test void rejectsOtherSnapshotsAndFixedRevision() {
        var jdbc=new Database(); UUID snapshot=UUID.randomUUID();
        jdbc.row=row(snapshot,UUID.randomUUID(),UUID.randomUUID(),workspace);
        var service=new ManualWorkflowService(jdbc,new Imports(workspace),new Reports(UUID.randomUUID(),snapshot),snapshot.toString(),commit,workspace.toString());
        assertThrows(ResponseStatusException.class,()->service.create(UUID.randomUUID(),"a","b","c","d"));
        jdbc.row.put("commit_sha","b".repeat(40));
        assertThrows(ResponseStatusException.class,service::fixtures);
        assertNull(jdbc.inserted);
    }
    @Test void rejectsMissingOrMismatchedSnapshotOwner() {
        var jdbc=new Database(); UUID snapshot=UUID.randomUUID();
        jdbc.row=row(snapshot,UUID.randomUUID(),UUID.randomUUID(),workspace); jdbc.count=0;
        var imports=new Imports(workspace);
        var service=new ManualWorkflowService(jdbc,imports,new Reports(UUID.randomUUID(),snapshot),snapshot.toString(),commit,workspace.toString());
        assertThrows(ResponseStatusException.class,()->service.create(snapshot,"a","b","c","d"));
        assertNull(imports.verified); assertNull(jdbc.inserted);
    }
    @Test void verifiesSharedBaseAndRejectsAnUnrelatedOwner() throws Exception {
        UUID owner=UUID.randomUUID(),report=UUID.randomUUID();
        Path base=workspace.resolve(owner.toString()).resolve("base"); Files.createDirectories(base);
        Files.writeString(base.resolve("pom.xml"),"fixture");
        String hash=GitSnapshot.contentHash(base);
        var jdbc=new Database(); jdbc.source=new String[]{base.toString(),hash};
        var imports=new SnapshotImportService(jdbc,new TransactionTemplate(),workspace.toString(),workspace.toString());
        assertEquals(true,imports.verifySnapshot(report).get("verified"));
        jdbc.count=0;
        assertThrows(ResponseStatusException.class,()->imports.verifySnapshot(report));
    }
    private Map<String,Object> row(UUID snapshot,UUID project,UUID owner,Path repository) {
        Map<String,Object> row=new HashMap<>(); row.put("id",snapshot); row.put("project_id",project);
        row.put("commit_sha",commit); row.put("module_path","fixtures/checkout-timeout");
        row.put("build_tool","MAVEN"); row.put("content_hash","a".repeat(64));
        row.put("repository_url",repository.toAbsolutePath().toString());
        row.put("source_location",workspace.resolve(owner.toString()).resolve("base").toString()); return row;
    }
}
