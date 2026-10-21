package com.bugtrace.backend;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Browser facade: case identifiers only, no repository paths, commands or grading access. */
@Service
public class BenchmarkBrowserService {
    public record Create(UUID requestId,String caseId) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final BenchmarkRegistrationService registrations;private final BenchmarkSnapshotService snapshots;
    private final InvestigationService investigations;private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkBrowserService(JdbcTemplate jdbc,PlatformTransactionManager manager,BenchmarkRegistrationService registrations,BenchmarkSnapshotService snapshots,InvestigationService investigations) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.registrations=registrations;this.snapshots=snapshots;this.investigations=investigations;
    }
    public Map<String,Object> catalog() {
        var cases=new ArrayList<Map<String,Object>>();var validated=registrations.browserCatalog();
        for(var c:validated){var report=report(c);cases.add(Map.of("caseId",c.caseId(),"collectionVersion",c.collectionVersion(),"releaseCommit",c.releaseCommit(),"modulePath",c.modulePath(),"bugReport",report.get("bugReport"),"expectedBehavior",report.get("expectedBehavior")));}
        return Map.of("cases",cases,"executionSupported",true,"liveSmokeReady",false);
    }
    public List<Map<String,Object>> recent() {
        String sql="""
            SELECT i.id AS investigation_id,i.title,i.snapshot_id,r.case_id,r.collection_version,i.created_at
            FROM investigations i JOIN benchmark_browser_requests b ON b.investigation_id=i.id
            JOIN benchmark_case_registrations r ON r.id=b.registration_id
            UNION
            SELECT i.id AS investigation_id,i.title,i.snapshot_id,r.case_id,r.collection_version,i.created_at
            FROM investigations i JOIN benchmark_snapshot_imports b ON b.investigation_id=i.id
            JOIN benchmark_case_registrations r ON r.id=b.registration_id
            ORDER BY created_at DESC,investigation_id LIMIT 50
            """;
        if(TeamAccessContext.project()==null)return jdbc.queryForList(sql);
        String union=sql.substring(0,sql.indexOf("ORDER BY created_at"));
        return jdbc.queryForList("SELECT b.* FROM ("+union+") b JOIN investigations own ON own.id=b.investigation_id WHERE own.project_id=? ORDER BY b.created_at DESC,b.investigation_id LIMIT 50",TeamAccessContext.project());
    }
    public Map<String,Object> create(Create input) {
        if(input==null||input.requestId()==null||input.caseId()==null||!input.caseId().matches("case-00[1-6]"))throw bad("Select a registered public case and supply a request UUID");
        var saved=registrations.register(new BenchmarkRegistrationService.Create(input.caseId()));
        UUID registration=(UUID)saved.get("registrationId");var c=json.convertValue(saved.get("registration"),BenchmarkCatalog.Registration.class);var report=report(c);
        UUID investigation=tx.execute(status->{
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",input.requestId().toString());
            var prior=jdbc.queryForList("SELECT * FROM benchmark_browser_requests WHERE request_id=?",input.requestId());
            if(!prior.isEmpty()) {var row=prior.getFirst();if(!input.caseId().equals(row.get("case_id"))||!registration.equals(row.get("registration_id")))throw bad("Request UUID already belongs to another case");return (UUID)row.get("investigation_id");}
            UUID project=TeamAccessContext.project();if(project==null)project=investigations.createProject("Benchmark "+c.caseId(),"Public collection "+c.collectionVersion()).id();
            var created=investigations.createInvestigation(project,c.repository(),c.releaseCommit(),"Benchmark "+c.caseId(),report.get("bugReport"),report.get("expectedBehavior"),"Public report only; no experiment has executed.");
            jdbc.update("INSERT INTO benchmark_browser_requests(request_id,case_id,registration_id,investigation_id) VALUES (?,?,?,?)",input.requestId(),c.caseId(),registration,created.id());return created.id();
        });return detail(investigation);
    }
    public Map<String,Object> detail(UUID investigation) {
        var rows=jdbc.queryForList("""
            SELECT i.id,i.project_id,i.title,i.snapshot_id,i.bug_description,i.expected_behavior,
                q.registration_id AS browser_registration,b.registration_id AS imported_registration,
                COALESCE(q.registration_id,b.registration_id) AS registration_id
            FROM investigations i LEFT JOIN benchmark_browser_requests q ON q.investigation_id=i.id
            LEFT JOIN benchmark_snapshot_imports b ON b.investigation_id=i.id
            WHERE i.id=? AND (q.registration_id IS NOT NULL OR b.registration_id IS NOT NULL)
            """,investigation);
        if(rows.isEmpty()) {
            Integer pending=jdbc.queryForObject("SELECT count(*) FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=? AND s.module_path LIKE 'cases/case-%'",Integer.class,investigation);
            if(pending!=null&&pending>0)throw bad("Benchmark snapshot is not registered; use the benchmark import workflow");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No benchmark investigation binding");
        }
        var row=rows.getFirst();TeamAccessContext.requireProject((UUID)row.get("project_id"));
        if(row.get("browser_registration")!=null&&row.get("imported_registration")!=null&&!row.get("browser_registration").equals(row.get("imported_registration")))throw bad("Imported case differs from browser case selection");
        UUID registration=(UUID)row.get("registration_id");var c=json.convertValue(registrations.verify(registration).get("registration"),BenchmarkCatalog.Registration.class);
        var result=new LinkedHashMap<String,Object>();result.put("investigationId",investigation);result.put("registrationId",registration);result.put("snapshotId",row.get("snapshot_id"));
        result.put("title",row.get("title"));result.put("caseId",c.caseId());result.put("collectionVersion",c.collectionVersion());result.put("releaseCommit",c.releaseCommit());
        result.put("bugReport",row.get("bug_description"));result.put("expectedBehavior",row.get("expected_behavior"));
        boolean imported=row.get("imported_registration")!=null;result.put("imported",imported);
        result.put("verification",imported?snapshots.verify(registration,investigation):null);result.put("executionSupported",true);result.put("liveSmokeReady",false);return result;
    }
    public Map<String,Object> capture(UUID investigation) {
        var selected=detail(investigation);UUID registration=(UUID)selected.get("registrationId");
        snapshots.capture(registration,new BenchmarkSnapshotService.Import(investigation));return detail(investigation);
    }
    private Map<String,String> report(BenchmarkCatalog.Registration c) {
        try {
            Path file=Path.of(c.repository()).resolve(c.modulePath()).resolve("case.json");
            if(Files.isSymbolicLink(file)||!file.toRealPath().equals(file.toAbsolutePath().normalize()))throw new IllegalArgumentException("Linked case report");
            byte[] bytes;
            try(var stream=Files.newInputStream(file)){bytes=stream.readNBytes(262145);}
            if(bytes.length>262144||!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(c.files().get("case.json")))throw new IllegalArgumentException("Public report hash changed");
            var report=json.readTree(bytes);String bug=report.path("bugReport").asText(),expected=report.path("expectedBehavior").asText();
            if(!c.caseId().equals(report.path("id").asText())||bug.isBlank()||expected.isBlank()||bug.length()>20000||expected.length()>20000)throw new IllegalArgumentException("Invalid public case report");
            return Map.of("bugReport",bug,"expectedBehavior",expected);
        }catch(Exception failure){throw bad("Validated public case report unavailable: "+failure.getMessage());}
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
