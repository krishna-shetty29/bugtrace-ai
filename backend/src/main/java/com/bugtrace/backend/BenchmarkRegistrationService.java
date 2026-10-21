package com.bugtrace.backend;

import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BenchmarkRegistrationService {
    private final JdbcTemplate jdbc;private final boolean enabled;private final BenchmarkCatalog catalog;
    private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkRegistrationService(JdbcTemplate jdbc,
        @Value("${bugtrace.benchmark.enabled:false}") boolean enabled,
        @Value("${bugtrace.benchmark.repository:${user.home}/bbp}") String repository,
        @Value("${bugtrace.benchmark.release-commit:6c4f8856bb7e4d49e953a8b086d594d2f505838d}") String commit) {
        this.jdbc=jdbc;this.enabled=enabled;catalog=new BenchmarkCatalog(Path.of(repository),commit,BenchmarkCatalog.MANIFEST);
    }
    public record Create(String caseId) {}
    public Map<String,Object> available() {return Map.of("enabled",enabled,"registrations",jdbc.queryForList("SELECT id,case_id,collection_version,release_commit,snapshot_content_hash,created_at FROM benchmark_case_registrations ORDER BY case_id"),"executionSupported",false);}
    public Map<String,Object> register(Create input) {
        if(input==null||input.caseId()==null||!input.caseId().matches("case-00[1-6]"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select case-001 through case-006; paths are not accepted");
        var cases=validated();var document=cases.stream().filter(c->c.caseId().equals(input.caseId())).findFirst().orElseThrow();
        String encoded=json.writeValueAsString(document),hash=DiagnosisIntegrity.hash(json.readTree(encoded));
        UUID id=UUID.nameUUIDFromBytes((document.repository()+"\n"+document.releaseCommit()+"\n"+document.caseId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
            INSERT INTO benchmark_case_registrations(id,case_id,collection_version,release_commit,repository,module_path,snapshot_content_hash,registration,registration_hash)
            VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT(id) DO NOTHING
            """,id,document.caseId(),document.collectionVersion(),document.releaseCommit(),document.repository(),document.modulePath(),document.snapshotContentHash(),encoded,hash);
        var saved=jdbc.queryForMap("SELECT * FROM benchmark_case_registrations WHERE id=?",id);
        if(!hash.equals(saved.get("registration_hash"))||!json.readTree(encoded).equals(json.readTree(saved.get("registration").toString())))throw new ResponseStatusException(HttpStatus.CONFLICT,"Existing registration differs; do not overwrite a released registration");
        return view(saved,true);
    }
    public Map<String,Object> verify(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM benchmark_case_registrations WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Registration not found");
        var saved=rows.getFirst();var cases=validated();
        var current=cases.stream().filter(c->c.caseId().equals(saved.get("case_id"))).findFirst().orElseThrow();
        String encoded=json.writeValueAsString(current);
        if(!DiagnosisIntegrity.hash(json.readTree(encoded)).equals(saved.get("registration_hash"))||!json.readTree(encoded).equals(json.readTree(saved.get("registration").toString())))throw new ResponseStatusException(HttpStatus.CONFLICT,"Saved registration does not match current pinned public release");
        return view(saved,true);
    }
    List<BenchmarkCatalog.Registration> browserCatalog() {return validated();}
    private List<BenchmarkCatalog.Registration> validated() {
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Benchmark registration is disabled; enable its separate configuration");
        try{return catalog.validate();}catch(Exception ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Public release validation failed: "+ex.getMessage());}
    }
    private Map<String,Object> view(Map<String,Object> saved,boolean verified) {
        var result=new LinkedHashMap<String,Object>();result.put("registrationId",saved.get("id"));result.put("registration",json.readTree(saved.get("registration").toString()));result.put("verified",verified);result.put("registered",true);result.put("snapshotImported",false);result.put("executionSupported",false);return result;
    }
}
