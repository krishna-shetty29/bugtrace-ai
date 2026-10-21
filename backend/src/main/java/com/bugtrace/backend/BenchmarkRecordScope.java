package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Case ownership comes from database bindings and verified captured bytes, never request paths. */
@Service
public class BenchmarkRecordScope {
    public record Context(UUID registrationId,UUID investigationId,UUID snapshotId,String contentHash,
        Path base,BenchmarkCatalog.Registration registration) {}
    private final JdbcTemplate jdbc;
    private final BenchmarkRegistrationService registrations;
    private final BenchmarkSnapshotService snapshots;
    private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkRecordScope(JdbcTemplate jdbc,BenchmarkRegistrationService registrations,BenchmarkSnapshotService snapshots) {
        this.jdbc=jdbc;this.registrations=registrations;this.snapshots=snapshots;
    }
    public Context optional(UUID investigation) {
        var rows=jdbc.queryForList("SELECT registration_id FROM benchmark_snapshot_imports WHERE investigation_id=?",investigation);
        if(rows.isEmpty()) {
            Integer benchmark=jdbc.queryForObject("SELECT count(*) FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=? AND s.module_path LIKE 'cases/case-%'",Integer.class,investigation);
            if(benchmark!=null&&benchmark>0)throw reject("Benchmark snapshot has no registered case binding");
            return null;
        }
        UUID registration=(UUID)rows.getFirst().get("registration_id");
        var verified=snapshots.verify(registration,investigation);
        var metadata=json.convertValue(registrations.verify(registration).get("registration"),BenchmarkCatalog.Registration.class);
        return new Context(registration,investigation,(UUID)verified.get("snapshotId"),verified.get("contentHash").toString(),Path.of(verified.get("basePath").toString()),metadata);
    }
    public Context required(UUID investigation) {var result=optional(investigation);if(result==null)throw reject("A registered benchmark investigation is required");return result;}
    public Map<String,Object> run(Context c,UUID run,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM benchmark_reproduction_runs WHERE id=? AND investigation_id=?"+(lock?" FOR UPDATE":""),run,c.investigationId());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Run does not belong to this investigation");
        var row=rows.getFirst();
        if(!c.registrationId().equals(row.get("registration_id"))||!c.snapshotId().equals(row.get("snapshot_id"))||!c.contentHash().equals(row.get("content_hash")))throw reject("Run differs from its registered snapshot binding");
        return row;
    }
    static void validateEvidence(Context c,AiEvidenceService.Evidence evidence) {
        try {
            if(!"CURRENT_SNAPSHOT_CODE".equals(evidence.origin())||evidence.reference()==null||!"CODE".equals(evidence.reference().kind())
                ||!c.registration().releaseCommit().equals(evidence.revision())||!c.registration().files().containsKey(evidence.sourceRef()))
                throw new IllegalArgumentException("Only registered current case files are permitted");
            String[] lines=Files.readString(c.base().resolve(evidence.sourceRef())).split("\\R",-1);
            if(evidence.startLine()<1||evidence.endLine()<evidence.startLine()||evidence.endLine()>lines.length)throw new IllegalArgumentException("Invalid source line range");
            String expected=String.join("\n",Arrays.copyOfRange(lines,evidence.startLine()-1,evidence.endLine()))+"\n";
            if(!expected.equals(evidence.text())||!ContextDocuments.hash(expected).equals(evidence.sourceHash()))throw new IllegalArgumentException("Evidence differs from captured source");
        }catch(Exception failure){throw reject("Benchmark evidence rejected: "+failure.getMessage());}
    }
    static void selectedReferences(List<AiEvidenceService.Ref> requested,List<AiEvidenceService.Ref> selected) {
        if(requested==null||requested.isEmpty()||requested.size()>8||new HashSet<>(requested).size()!=requested.size()||!new HashSet<>(selected).containsAll(requested))
            throw reject("Evidence references must be selected by this run");
    }
    static ResponseStatusException reject(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
