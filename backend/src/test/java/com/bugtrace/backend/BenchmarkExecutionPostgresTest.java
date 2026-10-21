package com.bugtrace.backend;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL lifecycle, synthetic provider and worker completions. Not a Docker reproduction. */
@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_EXECUTION_PG_TEST",matches="true")
class BenchmarkExecutionPostgresTest {
    @TempDir Path temp;
    @AfterEach void cleanup()throws Exception {try(var walk=Files.walk(temp)){for(Path p:walk.filter(Files::isRegularFile).toList())if(Files.getFileStore(p).supportsFileAttributeView("dos"))Files.setAttribute(p,"dos:readonly",false);}}
    @Test void dispatchReplayRepairBudgetOwnershipAndReload()throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Use local PostgreSQL");
        String user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD"),schema="benchmark_execution_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);
                try(var stream=new ClassPathResource("db/migration/V2__create_initial_tables.sql").getInputStream()){statement.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
                for(String file:List.of("V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V9__add_model_calls.sql","V18__add_benchmark_case_registrations.sql","V19__add_benchmark_snapshot_imports.sql","V20__add_benchmark_reproduction_runs.sql","V21__add_benchmark_case_records.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
                // These two columns originate in V12/V13, whose other tables are outside this isolated fixture.
                statement.execute("ALTER TABLE execution_jobs ADD COLUMN generated_test JSONB, ADD COLUMN not_after TIMESTAMPTZ");
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V23__add_benchmark_execution_links.sql"));
                var data=new DriverManagerDataSource(url,user,password);var props=new Properties();props.setProperty("currentSchema",schema);data.setConnectionProperties(props);
                var jdbc=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);var tx=new TransactionTemplate(manager);var json=JsonMapper.builder().build();
                String repository=System.getenv().getOrDefault("BENCHMARK_TEST_REPOSITORY",Path.of(System.getProperty("user.home"),"bbp").toString()),commit="6c4f8856bb7e4d49e953a8b086d594d2f505838d";
                var registrations=new BenchmarkRegistrationService(jdbc,true,repository,commit);UUID registration=(UUID)registrations.register(new BenchmarkRegistrationService.Create("case-001")).get("registrationId");
                var investigations=new InvestigationService(jdbc);var project=investigations.createProject("Execution SQL fixture","Not performance evidence");var inv=investigations.createInvestigation(project.id(),repository,commit,"Case","Report","Expected","Not executed");
                var snapshots=new BenchmarkSnapshotService(jdbc,manager,registrations,temp.resolve("workspace").toString());var imported=snapshots.capture(registration,new BenchmarkSnapshotService.Import(inv.id()));
                String file="src/main/java/com/bugtrace/benchmark/CheckoutProcessor.java";String raw=Files.readString(Path.of(imported.get("basePath").toString()).resolve(file));String[] lines=raw.split("\\R",-1);int last=lines.length-(lines[lines.length-1].isEmpty()?1:0);String snippet=String.join("\n",Arrays.copyOfRange(lines,0,last))+"\n";
                var ref=new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"fixture",null);var item=new AiEvidenceService.Evidence("e1",ref,"CURRENT_SNAPSHOT_CODE",file,commit,"SOURCE_LINE",1,last,ContextDocuments.hash(snippet),snippet);
                var evidence=new AiEvidenceService(jdbc,null,null){@Override public List<Evidence> available(UUID id){return List.of(item);}@Override public List<Evidence> resolve(UUID id,List<Ref> refs){if(!inv.id().equals(id)||!List.of(ref).equals(refs))throw new IllegalArgumentException("Foreign evidence");return List.of(item);}};
                String source="package com.bugtrace.benchmark; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.Timeout; import static org.junit.jupiter.api.Assertions.*; public class GeneratedBenchmarkReproductionTest { @Test @Timeout(2) void fixture(){assertEquals(1,2,\"DUPLICATE\");} }";
                var counter=new java.util.concurrent.atomic.AtomicInteger();
                AiProvider provider=request->{int n=counter.incrementAndGet();String answer=request.instructions().equals(BenchmarkReproduction.PLAN_INSTRUCTIONS)?json.writeValueAsString(new BenchmarkReproduction.Plan("INVESTIGATE","Synthetic hypothesis",List.of("e1"),List.of(),"No execution")):json.writeValueAsString(new BenchmarkReproduction.Spec(source+"\n// version "+n,"One charge","Response loss",List.of("e1"),"DUPLICATE","Synthetic proposal"));return new AiProvider.Response("mock-v1","fixture",answer,new AiProvider.Usage(null,null,null,"MOCK_NOT_MEASURED"),false);};
                var settings=new AiSettings("mock","mock-v1","",60,1500);var calls=new AiCallService(jdbc,evidence,provider,settings);var queue=new DurableQueueService(jdbc);
                var search=new SnapshotSearchService(jdbc,null){@Override public Results search(UUID id,String query,SnapshotSearchEngine.Kind kind,int limit){return new Results(id,inv.snapshotId(),imported.get("contentHash").toString(),query,"ALL",4,0,false,false,0,1,1,0,0,List.of(),List.of());}};
                var workflow=new BenchmarkReproductionService(jdbc,manager,snapshots,registrations,calls,evidence,settings,search,queue);UUID run=UUID.randomUUID();workflow.create(inv.id(),new BenchmarkReproductionService.Create(run,"checkout",List.of(ref),8,12000,1800));workflow.advance(inv.id(),run,new BenchmarkReproductionService.Step(UUID.randomUUID()));workflow.advance(inv.id(),run,new BenchmarkReproductionService.Step(UUID.randomUUID()));
                var scope=new BenchmarkRecordScope(jdbc,registrations,snapshots);var service=new BenchmarkExecutionService(jdbc,manager,scope,queue,calls,settings);
                assertThrows(Exception.class,()->queue.enqueue(inv.id(),new QueueContracts.CreateJob(QueueContracts.Operation.RUN_SELECTED_TEST,BenchmarkExecutionPolicy.CLASS,120)));
                assertThrows(Exception.class,()->service.execute(inv.id(),run,new BenchmarkExecutionService.Execute(UUID.randomUUID(),false)));
                UUID request=UUID.randomUUID();var execute=new BenchmarkExecutionService.Execute(request,true);
                UUID first;
                try(var pool=Executors.newFixedThreadPool(2)) {
                    var a=pool.submit(()->service.execute(inv.id(),run,execute));var b=pool.submit(()->service.execute(inv.id(),run,execute));
                    first=(UUID)a.get(60,TimeUnit.SECONDS).get("executionId");assertEquals(first,b.get(60,TimeUnit.SECONDS).get("executionId"));
                }
                assertEquals(1,jdbc.queryForObject("SELECT experiment_jobs FROM benchmark_reproduction_runs WHERE id=?",Integer.class,run));
                assertThrows(Exception.class,()->service.get(inv.id(),UUID.randomUUID(),first));
                assertThrows(Exception.class,()->service.execute(inv.id(),run,new BenchmarkExecutionService.Execute(UUID.randomUUID(),true)));
                assertThrows(Exception.class,()->workflow.stop(inv.id(),run));
                UUID execution=first;
                for(int attempt=1;attempt<=3;attempt++) {
                    var claim=tx.execute(status->queue.claim(new QueueContracts.Claim("fixture-worker",inv.snapshotId())));assertNotNull(claim);assertEquals(execution,claim.get("jobId"));
                    String stdout=attempt<3?"[ERROR] COMPILATION ERROR\n[ERROR] /workspace/GeneratedBenchmarkReproductionTest.java:[1,2] cannot find symbol\n[ERROR] maven-compiler-plugin Compilation failure":"Assertion failed";
                    var binding=json.readTree(jdbc.queryForObject("SELECT benchmark_binding::text FROM execution_jobs WHERE id=?",String.class,execution));
                    var metadata=new LinkedHashMap<String,Object>();metadata.put("executionId",claim.get("executionId").toString());metadata.put("snapshotId",inv.snapshotId().toString());metadata.put("snapshotContentHash",imported.get("contentHash"));metadata.put("operation","RUN_SELECTED_TEST");metadata.put("selectedTest",BenchmarkExecutionPolicy.CLASS);metadata.put("state","FAILED");metadata.put("classification",attempt<3?"COMPILATION_FAILED":"ASSERTION_FAILED");metadata.put("exitCode",1);metadata.put("notAfter",binding.path("notAfter").asText());metadata.put("timeoutSeconds",binding.path("timeoutSeconds").asInt());
                    metadata.put("benchmarkRegistrationId",registration);metadata.put("benchmarkInvestigationId",inv.id());metadata.put("benchmarkCaseId","case-001");metadata.put("registeredEnvironment",binding.get("environment"));metadata.put("snapshotVerifiedAfter",true);metadata.put("containerJava21Verified",true);metadata.put("environmentValidatedContainers",2);
                    metadata.put("generatedTest",Map.of("path",BenchmarkExecutionPolicy.PATH,"className",BenchmarkExecutionPolicy.CLASS,"requestedSourceSha256",binding.path("sourceSha256").asText(),"installedSourceSha256",binding.path("sourceSha256").asText(),"unchangedAfterRun",true));
                    metadata.put("stdout",Map.of("sha256",ContextDocuments.hash(stdout)));metadata.put("stderr",Map.of("sha256",ContextDocuments.hash("")));
                    metadata.put("junit",attempt<3?Map.of():Map.of("tests",1,"errors",0,"skipped",0,"failures",1,"files",List.of(Map.of("name","TEST-"+BenchmarkExecutionPolicy.CLASS+".xml")),"details",List.of(Map.of("className",BenchmarkExecutionPolicy.CLASS,"type","org.opentest4j.AssertionFailedError","message","DUPLICATE expected 1 but was 2"))));
                    var completion=new QueueContracts.Complete((UUID)claim.get("leaseToken"),"fixture-worker",metadata,stdout,"");UUID completed=execution;
                    tx.execute(status->queue.complete(completed,completion));
                    if(attempt<3) {
                        var repair=new BenchmarkExecutionService.Repair(UUID.randomUUID());service.repair(inv.id(),run,repair);int callsBefore=counter.get();service.repair(inv.id(),run,repair);assertEquals(callsBefore,counter.get());
                        execution=(UUID)service.execute(inv.id(),run,new BenchmarkExecutionService.Execute(UUID.randomUUID(),true)).get("executionId");
                    }
                }
                assertEquals(3,jdbc.queryForObject("SELECT experiment_jobs FROM benchmark_reproduction_runs WHERE id=?",Integer.class,run));assertEquals(2,jdbc.queryForObject("SELECT compiler_repairs FROM benchmark_reproduction_runs WHERE id=?",Integer.class,run));
                assertEquals(4,counter.get());assertThrows(Exception.class,()->service.repair(inv.id(),run,new BenchmarkExecutionService.Repair(UUID.randomUUID())));
                var restarted=new BenchmarkExecutionService(jdbc,manager,scope,queue,calls,settings);assertEquals(3,restarted.list(inv.id(),run).size());assertEquals("EXPECTED_FAILURE_OBSERVED",restarted.get(inv.id(),run,execution).get("observation"));
                var records=new BenchmarkCaseRecordsService(jdbc,manager,scope,evidence,search,restarted);var saved=records.export(inv.id(),run);assertEquals(saved.get("recordId"),records.export(inv.id(),run).get("recordId"));
                assertTrue((Boolean)snapshots.verify(registration,inv.id()).get("verified"));
                jdbc.update("UPDATE benchmark_reproduction_runs SET deadline=clock_timestamp()-INTERVAL '1 second' WHERE id=?",run);assertThrows(Exception.class,()->service.execute(inv.id(),run,new BenchmarkExecutionService.Execute(UUID.randomUUID(),true)));
            } finally {statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
