package com.bugtrace.backend;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_REPRODUCTION_PG_TEST",matches="true")
class BenchmarkReproductionPostgresTest {
    @TempDir Path temp;
    @AfterEach void writableCleanup()throws Exception {try(var walk=Files.walk(temp)){for(Path file:walk.filter(Files::isRegularFile).toList())if(Files.getFileStore(file).supportsFileAttributeView("dos"))Files.setAttribute(file,"dos:readonly",false);}}
    @Test void savesPlansDraftsAbstentionsBudgetsAndReplaysWithoutWorkerJobs()throws Exception {
        String schema="benchmark_reproduction_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("DB_URL"),user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD");assertNotNull(url);
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);
                try(var stream=new ClassPathResource("db/migration/V2__create_initial_tables.sql").getInputStream()){statement.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
                for(String file:List.of("V3__add_snapshot_import_metadata.sql","V9__add_model_calls.sql","V18__add_benchmark_case_registrations.sql","V19__add_benchmark_snapshot_imports.sql","V20__add_benchmark_reproduction_runs.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
                var data=new DriverManagerDataSource(url,user,password);var props=new Properties();props.setProperty("currentSchema",schema);data.setConnectionProperties(props);
                var jdbc=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);var json=JsonMapper.builder().build();
                String repository=Path.of(System.getProperty("user.home"),"bbp").toString(),commit="6c4f8856bb7e4d49e953a8b086d594d2f505838d";
                var registrations=new BenchmarkRegistrationService(jdbc,true,repository,commit);
                UUID registration=(UUID)registrations.register(new BenchmarkRegistrationService.Create("case-001")).get("registrationId");
                var investigations=new InvestigationService(jdbc);var project=investigations.createProject("Reproduction test","Temporary schema");var inv=investigations.createInvestigation(project.id(),repository,commit,"Source report","Report","Expected","No experiments");
                var snapshots=new BenchmarkSnapshotService(jdbc,manager,registrations,temp.resolve("workspace").toString());var imported=snapshots.capture(registration,new BenchmarkSnapshotService.Import(inv.id()));
                String sourcePath="src/main/java/com/bugtrace/benchmark/CheckoutProcessor.java";String text=Files.readString(Path.of(imported.get("basePath").toString()).resolve(sourcePath));String[] lines=text.split("\\R",-1);int last=lines.length-(lines[lines.length-1].isEmpty()?1:0);String snippet=String.join("\n",Arrays.copyOfRange(lines,0,last))+"\n";
                var ref=new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"test-source",null);
                var item=new AiEvidenceService.Evidence("e1",ref,"CURRENT_SNAPSHOT_CODE",sourcePath,commit,"SOURCE_LINE",1,last,ContextDocuments.hash(snippet),snippet);
                var evidence=new AiEvidenceService(jdbc,null,null){@Override public List<Evidence> available(UUID id){return List.of(item);}@Override public List<Evidence> resolve(UUID id,List<Ref> refs){if(!id.equals(inv.id())||!List.of(ref).equals(refs))throw new IllegalArgumentException("Foreign evidence");return List.of(item);}};
                AtomicInteger modelCalls=new AtomicInteger();AtomicBoolean abstain=new AtomicBoolean();
                AiProvider provider=request->{modelCalls.incrementAndGet();String answer=request.instructions().equals(BenchmarkReproduction.TEST_INSTRUCTIONS)?json.writeValueAsString(new BenchmarkReproduction.Spec(BenchmarkReproductionTest.source(),"Assertion","Controlled scenario",List.of("e1"),"expected","Fixture proposal only")):json.writeValueAsString(new BenchmarkReproduction.Plan(abstain.get()?"NEEDS_EVIDENCE":"INVESTIGATE","Advisory fixture",List.of("e1"),abstain.get()?List.of("Missing provider evidence"):List.of(),"No execution"));return new AiProvider.Response("mock-v1","test-only",answer,new AiProvider.Usage(null,null,null,"MOCK_NOT_MEASURED"),false);};
                var settings=new AiSettings("mock","mock-v1","",60,1500);var calls=new AiCallService(jdbc,evidence,provider,settings);
                var search=new SnapshotSearchService(jdbc,null){@Override public Results search(UUID id,String query,SnapshotSearchEngine.Kind kind,int limit){return new Results(id,inv.snapshotId(),imported.get("contentHash").toString(),query,"ALL",4,0,false,false,0,1,1,0,0,List.of(),List.of());}};
                var queue=new DurableQueueService(jdbc);var workflow=new BenchmarkReproductionService(jdbc,manager,snapshots,registrations,calls,evidence,settings,search,queue);
                UUID run=UUID.randomUUID();var create=new BenchmarkReproductionService.Create(run,"checkout",List.of(ref),2,3000,1800);
                assertEquals("READY_ANALYSIS",workflow.create(inv.id(),create).get("state"));
                var first=new BenchmarkReproductionService.Step(UUID.randomUUID());assertEquals("PLAN_READY",workflow.advance(inv.id(),run,first).get("state"));assertEquals("PLAN_READY",workflow.advance(inv.id(),run,first).get("state"));assertEquals(1,modelCalls.get());
                var second=new BenchmarkReproductionService.Step(UUID.randomUUID());assertEquals("TEST_READY",workflow.advance(inv.id(),run,second).get("state"));assertEquals(2,modelCalls.get());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM executions",Integer.class));
                var restart=new BenchmarkReproductionService(jdbc,manager,snapshots,registrations,calls,evidence,settings,search,queue);assertEquals("TEST_READY",restart.get(inv.id(),run).get("state"));assertEquals(true,restart.get(inv.id(),run).get("executionSupported"));workflow.stop(inv.id(),run);
                abstain.set(true);UUID negative=UUID.randomUUID();workflow.create(inv.id(),new BenchmarkReproductionService.Create(negative,"checkout",List.of(ref),2,3000,1800));var noPatch=workflow.advance(inv.id(),negative,new BenchmarkReproductionService.Step(UUID.randomUUID()));assertEquals("FINISHED_NO_PATCH",noPatch.get("state"));assertNull(noPatch.get("source_document"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM executions",Integer.class));
                abstain.set(false);UUID limited=UUID.randomUUID();workflow.create(inv.id(),new BenchmarkReproductionService.Create(limited,"checkout",List.of(ref),1,1500,1800));workflow.advance(inv.id(),limited,new BenchmarkReproductionService.Step(UUID.randomUUID()));int before=modelCalls.get();assertEquals("BUDGET_EXHAUSTED",workflow.advance(inv.id(),limited,new BenchmarkReproductionService.Step(UUID.randomUUID())).get("state"));assertEquals(before,modelCalls.get());
                UUID expired=UUID.randomUUID();workflow.create(inv.id(),new BenchmarkReproductionService.Create(expired,"checkout",List.of(ref),2,3000,1800));
                jdbc.update("UPDATE benchmark_reproduction_runs SET deadline=clock_timestamp()-INTERVAL '1 second' WHERE id=?",expired);
                int beforeExpired=modelCalls.get();assertEquals("BUDGET_EXHAUSTED",workflow.advance(inv.id(),expired,new BenchmarkReproductionService.Step(UUID.randomUUID())).get("state"));assertEquals(beforeExpired,modelCalls.get());
                UUID tokens=UUID.randomUUID();workflow.create(inv.id(),new BenchmarkReproductionService.Create(tokens,"checkout",List.of(ref),2,1500,1800));
                assertEquals("PLAN_READY",workflow.advance(inv.id(),tokens,new BenchmarkReproductionService.Step(UUID.randomUUID())).get("state"));
                int beforeTokens=modelCalls.get();assertEquals("BUDGET_EXHAUSTED",workflow.advance(inv.id(),tokens,new BenchmarkReproductionService.Step(UUID.randomUUID())).get("state"));assertEquals(beforeTokens,modelCalls.get());
                UUID interrupted=UUID.randomUUID();jdbc.update("UPDATE benchmark_reproduction_runs SET state='BUSY' WHERE id=?",limited);workflow.recover();assertEquals("INTERRUPTED",workflow.get(inv.id(),limited).get("state"));
            }finally{statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
