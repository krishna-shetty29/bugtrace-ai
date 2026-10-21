package com.bugtrace.backend;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Separate registration-bound allowlist. Existing checkout import policy is unchanged. */
@Service
public class BenchmarkSnapshotService {
    private final JdbcTemplate jdbc;private final BenchmarkRegistrationService registrations;
    private final TransactionTemplate tx;private final Path workspace;
    private final JsonMapper json=JsonMapper.builder().build();
    public record Import(UUID investigationId){}
    public BenchmarkSnapshotService(JdbcTemplate jdbc,PlatformTransactionManager manager,BenchmarkRegistrationService registrations,@Value("${bugtrace.workspace-root}") String workspace) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.registrations=registrations;this.workspace=Path.of(workspace).toAbsolutePath().normalize();
    }
    private BenchmarkCatalog.Registration registered(UUID registration) {
        return json.convertValue(registrations.verify(registration).get("registration"),BenchmarkCatalog.Registration.class);
    }
    public synchronized Map<String,Object> capture(UUID registrationId,Import input) {
        if(input==null||input.investigationId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supply a new investigation UUID");
        var registration=registered(registrationId);UUID investigation=input.investigationId();Path[] created={null};
        try{return tx.execute(status->{
            var row=owner(investigation,true);
            var previous=jdbc.queryForList("SELECT registration_id FROM benchmark_snapshot_imports WHERE investigation_id=?",investigation);
            if(!previous.isEmpty()) {
                if(!registrationId.equals(previous.getFirst().get("registration_id")))throw conflict("Investigation is bound to another benchmark case");
                return verified(registrationId,investigation,registration,row);
            }
            if(row.get("source_location")!=null)throw conflict("Snapshot already imported; it cannot be replaced or adopted");
            if(!registration.releaseCommit().equals(row.get("commit_sha"))||!Path.of(row.get("repository_url").toString()).toAbsolutePath().normalize().equals(Path.of(registration.repository())))throw conflict("Investigation repository/commit differs from registered public release");
            try {
                var capture=GitSnapshot.capture(Path.of(registration.repository()),registration.releaseCommit(),registration.modulePath(),workspace,investigation);
                created[0]=capture.base().getParent();
                if(!registration.releaseCommit().equals(capture.commit()))throw new IllegalArgumentException("Captured commit mismatch");
                String benchmarkHash=BenchmarkSnapshotFiles.verify(capture.base(),registration);
                if(!capture.contentHash().equals(GitSnapshot.contentHash(capture.base())))throw new IllegalArgumentException("Captured platform hash mismatch");
                jdbc.update("""
                    UPDATE repository_snapshots SET source_location=?,content_hash=?,build_tool=?,source_directory=?,test_directory=?,module_path=?,imported_at=clock_timestamp() WHERE id=?
                    """,capture.base().toString(),capture.contentHash(),capture.buildTool(),capture.sourceDirectory(),capture.testDirectory(),registration.modulePath(),row.get("snapshot_id"));
                jdbc.update("""
                    INSERT INTO benchmark_snapshot_imports(investigation_id,snapshot_id,registration_id,case_id,release_commit,benchmark_content_hash,platform_content_hash)
                    VALUES (?,?,?,?,?,?,?)
                    """,investigation,row.get("snapshot_id"),registrationId,registration.caseId(),registration.releaseCommit(),benchmarkHash,capture.contentHash());
                return verified(registrationId,investigation,registration,owner(investigation,false));
            }catch(ResponseStatusException ex){throw ex;}
            catch(Exception ex){throw conflict("Benchmark capture rejected: "+ex.getMessage());}
        });}catch(RuntimeException ex){
            if(created[0]!=null)try {
                Path expected=workspace.resolve(investigation.toString()).normalize();
                if(!created[0].equals(expected)||!expected.getParent().equals(workspace))throw new IllegalArgumentException("Unsafe cleanup target");
                GitSnapshot.deleteOwnedTree(expected);
            }catch(Exception cleanup){ex.addSuppressed(cleanup);}
            throw ex;
        }
    }
    public Map<String,Object> verify(UUID registrationId,UUID investigation) {
        return verified(registrationId,investigation,registered(registrationId),owner(investigation,false));
    }
    private Map<String,Object> owner(UUID investigation,boolean lock) {
        var rows=jdbc.queryForList("SELECT i.id,i.snapshot_id,s.repository_url,s.commit_sha,s.source_location,s.content_hash,s.module_path FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?"+(lock?" FOR UPDATE OF i,s":""),investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");return rows.getFirst();
    }
    private Map<String,Object> verified(UUID registrationId,UUID investigation,BenchmarkCatalog.Registration registration,Map<String,Object> row) {
        var bindings=jdbc.queryForList("SELECT * FROM benchmark_snapshot_imports WHERE investigation_id=? AND registration_id=?",investigation,registrationId);
        if(bindings.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No benchmark snapshot binding for this investigation and registration");
        var binding=bindings.getFirst();
        if(!Objects.equals(row.get("snapshot_id"),binding.get("snapshot_id"))||!registration.caseId().equals(binding.get("case_id"))||!registration.releaseCommit().equals(binding.get("release_commit"))||!registration.releaseCommit().equals(row.get("commit_sha"))||!registration.modulePath().equals(row.get("module_path"))||!registration.snapshotContentHash().equals(binding.get("benchmark_content_hash"))||!Path.of(registration.repository()).equals(Path.of(row.get("repository_url").toString()).toAbsolutePath().normalize()))throw conflict("Benchmark identity mismatch");
        try {
            Path expected=workspace.resolve(investigation.toString()).resolve("base");
            if(row.get("source_location")==null||!expected.equals(Path.of(row.get("source_location").toString()).toAbsolutePath().normalize()))throw new IllegalArgumentException("Benchmark workspace mismatch");
            String benchmarkHash=BenchmarkSnapshotFiles.verify(expected,registration),platformHash=GitSnapshot.contentHash(expected);
            if(!platformHash.equals(row.get("content_hash"))||!platformHash.equals(binding.get("platform_content_hash")))throw new IllegalArgumentException("Platform content changed");
            var result=new LinkedHashMap<String,Object>();result.put("registrationId",registrationId);result.put("investigationId",investigation);result.put("snapshotId",row.get("snapshot_id"));result.put("caseId",registration.caseId());result.put("modulePath",registration.modulePath());result.put("releaseCommit",registration.releaseCommit());result.put("benchmarkContentHash",benchmarkHash);result.put("contentHash",platformHash);result.put("basePath",expected.toString());result.put("verified",true);result.put("snapshotImported",true);result.put("executionSupported",false);return result;
        }catch(Exception ex){throw conflict("Benchmark snapshot verification failed: "+ex.getMessage());}
    }
    private static ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
}
