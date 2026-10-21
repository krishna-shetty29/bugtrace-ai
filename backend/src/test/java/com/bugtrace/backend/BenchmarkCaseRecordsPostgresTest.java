package com.bugtrace.backend;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_CASE_RECORDS_PG_TEST",matches="true")
class BenchmarkCaseRecordsPostgresTest {
    @TempDir Path temp;
    @AfterEach void writableCleanup()throws Exception {try(var walk=Files.walk(temp)){for(Path file:walk.filter(Files::isRegularFile).toList())if(Files.getFileStore(file).supportsFileAttributeView("dos"))Files.setAttribute(file,"dos:readonly",false);}}
    @Test void bindsRecordsAndRejectsCrossCaseRunCallChunkContextAndPrivateCanary()throws Exception {
        String schema="benchmark_records_test_"+UUID.randomUUID().toString().replace("-","");
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Use local PostgreSQL for this test");
        String user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);
                try(var stream=new ClassPathResource("db/migration/V2__create_initial_tables.sql").getInputStream()){statement.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
                for(String name:List.of("V3__add_snapshot_import_metadata.sql","V9__add_model_calls.sql","V18__add_benchmark_case_registrations.sql","V19__add_benchmark_snapshot_imports.sql","V20__add_benchmark_reproduction_runs.sql","V21__add_benchmark_case_records.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+name));
                // Minimal real SQL index tables avoid requiring pgvector for the ownership test.
                statement.execute("CREATE TABLE semantic_indexes(id UUID PRIMARY KEY,snapshot_id UUID NOT NULL,content_hash TEXT NOT NULL,state TEXT,chunker_version TEXT,created_at TIMESTAMPTZ DEFAULT clock_timestamp())");
                statement.execute("CREATE TABLE semantic_chunks(index_id UUID NOT NULL,chunk_key TEXT NOT NULL,file_path TEXT,source_text TEXT,text_hash TEXT,start_line INTEGER,end_line INTEGER)");
                var data=new DriverManagerDataSource(url,user,password);var props=new Properties();props.setProperty("currentSchema",schema);data.setConnectionProperties(props);
                var jdbc=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);var json=JsonMapper.builder().build();
                String repository=System.getenv().getOrDefault("BENCHMARK_TEST_REPOSITORY",Path.of(System.getProperty("user.home"),"bbp").toString());
                String commit="6c4f8856bb7e4d49e953a8b086d594d2f505838d";
                var registrations=new BenchmarkRegistrationService(jdbc,true,repository,commit);
                var snapshots=new BenchmarkSnapshotService(jdbc,manager,registrations,temp.resolve("workspace").toString());
                var scope=new BenchmarkRecordScope(jdbc,registrations,snapshots);
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),repository,temp.resolve("workspace").toString());
                var evidence=new AiEvidenceService(jdbc,imports,null,scope);var search=new SnapshotSearchService(jdbc,imports,scope);
                var investigations=new InvestigationService(jdbc);UUID[] inv=new UUID[3],runs=new UUID[3];var refs=new ArrayList<AiEvidenceService.Ref>();
                for(int i=0;i<3;i++) {
                    String caseId=i==1?"case-002":"case-001";
                    var registered=registrations.register(new BenchmarkRegistrationService.Create(caseId));UUID registration=(UUID)registered.get("registrationId");
                    var project=investigations.createProject("Case ownership fixture","Temporary schema");
                    var created=investigations.createInvestigation(project.id(),repository,commit,"Case test","Public report","Expected","Not executed");inv[i]=created.id();runs[i]=UUID.randomUUID();
                    var captured=snapshots.capture(registration,new BenchmarkSnapshotService.Import(inv[i]));
                    var c=scope.required(inv[i]);String file=c.registration().allowedProductionPaths().getFirst();
                    String raw=Files.readString(c.base().resolve(file));String[] lines=raw.split("\\R",-1);int count=lines.length-(lines[lines.length-1].isEmpty()?1:0);String text=String.join("\n",Arrays.copyOfRange(lines,0,count))+"\n";
                    UUID index=UUID.randomUUID();String key="case-source-"+i;
                    jdbc.update("INSERT INTO semantic_indexes(id,snapshot_id,content_hash,state,chunker_version) VALUES (?,?,?,'COMPLETE',?)",index,created.snapshotId(),captured.get("contentHash"),SemanticChunks.VERSION);
                    jdbc.update("INSERT INTO semantic_chunks(index_id,chunk_key,file_path,source_text,text_hash,start_line,end_line) VALUES (?,?,?,?,?,1,?)",index,key,file,text,ContextDocuments.hash(text),count);
                    refs.add(new AiEvidenceService.Ref("CODE",index,key,null));
                }
                var settings=new AiSettings("mock","mock-v1","",60,1500);
                AiProvider provider=request->{String id=json.readTree(request.input()).path("evidence").get(0).path("id").asText();String answer=request.instructions().equals(BenchmarkReproduction.TEST_INSTRUCTIONS)?json.writeValueAsString(new BenchmarkReproduction.Spec(BenchmarkReproductionTest.source(),"Assertion","Controlled scenario",List.of(id),"expected failure","Synthetic test fixture, not executed")):json.writeValueAsString(new BenchmarkReproduction.Plan("INVESTIGATE","Advisory source hypothesis",List.of(id),List.of(),"Not executed"));return new AiProvider.Response("mock-v1","test-only",answer,new AiProvider.Usage(null,null,null,"MOCK_NOT_MEASURED"),false);};
                var calls=new AiCallService(jdbc,evidence,provider,settings);var queue=new DurableQueueService(jdbc);
                var workflow=new BenchmarkReproductionService(jdbc,manager,snapshots,registrations,calls,evidence,settings,search,queue);
                for(int i=0;i<3;i++)workflow.create(inv[i],new BenchmarkReproductionService.Create(runs[i],"checkout",List.of(refs.get(i)),2,3000,1800));
                workflow.advance(inv[0],runs[0],new BenchmarkReproductionService.Step(UUID.randomUUID()));workflow.advance(inv[0],runs[0],new BenchmarkReproductionService.Step(UUID.randomUUID()));
                var records=new BenchmarkCaseRecordsService(jdbc,manager,scope,evidence,search);
                var saved=records.export(inv[0],runs[0]);UUID record=(UUID)saved.get("recordId");
                assertEquals(record,records.export(inv[0],runs[0]).get("recordId"));
                var document=json.valueToTree(saved.get("document"));assertEquals("case-001",document.path("identity").path("caseId").asText());assertEquals(1,document.path("generatedSource").path("version").asInt());assertTrue(document.path("executionIds").isEmpty());
                assertEquals(1,records.source(inv[0],runs[0],1).get("version"));
                assertThrows(Exception.class,()->records.source(inv[1],runs[0],1));
                assertThrows(Exception.class,()->records.get(inv[1],runs[1],record));
                assertThrows(Exception.class,()->records.get(inv[2],runs[2],record));
                UUID call=jdbc.queryForObject("SELECT call_id FROM benchmark_reproduction_steps WHERE run_id=? AND action='GENERATE_TEST'",UUID.class,runs[0]);
                assertThrows(Exception.class,()->records.modelCall(inv[1],runs[1],call));assertThrows(Exception.class,()->records.modelCall(inv[2],runs[2],call));
                assertThrows(Exception.class,()->evidence.resolve(inv[0],List.of(refs.get(1))));assertThrows(Exception.class,()->evidence.resolve(inv[0],List.of(refs.get(2))));
                assertThrows(Exception.class,()->records.resolve(inv[0],runs[0],new BenchmarkCaseRecordsService.EvidenceRequest(List.of(refs.get(1)))));
                assertThrows(Exception.class,()->evidence.resolve(inv[0],List.of(new AiEvidenceService.Ref("CONTEXT",UUID.randomUUID(),null,0))));
                Files.writeString(temp.resolve("PRIVATE_CANARY.txt"),"PRIVATE_GRADING_CANARY");
                var retrieved=records.retrieve(inv[0],runs[0],new BenchmarkCaseRecordsService.Search("PRIVATE_GRADING_CANARY",SnapshotSearchEngine.Kind.ALL,10));
                assertTrue(json.valueToTree(retrieved).path("document").path("results").path("hits").isEmpty());
                jdbc.update("INSERT INTO semantic_chunks(index_id,chunk_key,file_path,source_text,text_hash,start_line,end_line) VALUES (?,'private','../bbh/private.java','secret',?,1,1)",refs.get(0).indexId(),ContextDocuments.hash("secret"));
                assertThrows(Exception.class,()->evidence.resolve(inv[0],List.of(new AiEvidenceService.Ref("CODE",refs.get(0).indexId(),"private",null))));
                assertThrows(Exception.class,()->evidence.available(inv[0]));
                jdbc.update("DELETE FROM semantic_chunks WHERE chunk_key='private'");
                var restarted=new BenchmarkCaseRecordsService(jdbc,manager,new BenchmarkRecordScope(jdbc,registrations,snapshots),evidence,search);
                assertEquals(saved.get("documentSha256"),restarted.get(inv[0],runs[0],record).get("documentSha256"));
                jdbc.update("UPDATE benchmark_case_records SET document='{}'::jsonb WHERE id=?",record);
                assertThrows(Exception.class,()->records.get(inv[0],runs[0],record));
                jdbc.update("UPDATE benchmark_reproduction_runs SET retrieval_requests=30 WHERE id=?",runs[0]);
                assertThrows(Exception.class,()->records.retrieve(inv[0],runs[0],new BenchmarkCaseRecordsService.Search("checkout",SnapshotSearchEngine.Kind.ALL,10)));
                assertEquals(30,jdbc.queryForObject("SELECT retrieval_requests FROM benchmark_reproduction_runs WHERE id=?",Integer.class,runs[0]));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM executions",Integer.class));
            }finally{statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
