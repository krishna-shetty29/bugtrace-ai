package com.bugtrace.backend;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DIAGNOSIS_PG_TEST",matches="true")
class DiagnosisPostgresTest {
    @TempDir Path workspace;
    static final class Embeddings extends OllamaEmbeddings {
        Embeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return "a".repeat(64);}
        @Override public List<String> embed(List<String> input,Duration timeout){return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();}
    }
    @Test void validatesDiagnosisLinksPersistenceOwnershipAndNoChangeAuthority() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")),schema="agent_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){assertTrue(row.next());assertEquals(1,row.getInt(1),"Complete Step 25 database setup first");}
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);var jdbc=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql","V9__add_model_calls.sql","V10__add_bounded_agent_roles.sql","V11__add_role_tool_gateway.sql","V12__add_generated_reproduction.sql","V13__add_bounded_investigation_loop.sql","V14__allow_unstarted_execution_errors.sql","V15__add_controlled_scenario_batches.sql","V16__add_evidence_linked_diagnoses.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge() {}\n}\n");Files.writeString(base.resolve("README.md"),"# Checkout\nRetry after a lost response.\n");Files.createDirectories(base.resolve("src/test/java/demo"));Files.writeString(base.resolve("src/test/java/demo/RetryTest.java"),"package demo; class RetryTest {}\n");String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"AI integration test");jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash,"fixtures/checkout-timeout");
                for(UUID id:List.of(owner,other))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",id,project,snapshot,"AI test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),workspace.toString(),workspace.toString());var embeds=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);var semantic=new SemanticRetrievalService(jdbc,imports,search,graphs,embeds,manager);semantic.build(owner);
                var documents=new DocumentContextService(jdbc,imports,semantic,embeds,manager,workspace.toString());documents.build(owner,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));var evidence=new AiEvidenceService(jdbc,imports,documents);var choices=evidence.available(owner);assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CODE")));assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CONTEXT")));
                var refs=choices.stream().filter(item->item.reference().kind().equals("CODE")&&item.sourceRef().endsWith(".java")).limit(4).map(AiEvidenceService.Evidence::reference).toList();
                assertFalse(refs.isEmpty());
                var settings=new AiSettings("mock","mock-v1","",60,1500);var calls=new AiCallService(jdbc,evidence,new MockAiProvider(),settings);
                var queue=new DurableQueueService(jdbc);var tx=new TransactionTemplate(manager);
                var diagnosis=new DiagnosisService(jdbc,manager,calls,evidence,settings,imports);
                var selectedIds=evidence.resolve(owner,refs).stream().map(AiEvidenceService.Evidence::id).toList();
                var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
                var thin=new GeneratedReproduction.Spec(ControlledScenarioSource.entryPoint("RETRY_AFTER_RESPONSE_LOSS",3),ControlledScenarioSource.ASSERTION,ControlledScenarioSource.fault("RETRY_AFTER_RESPONSE_LOSS"),selectedIds,ControlledScenarioSource.SIGNATURE,"Controlled test proposal");
                var spec=ControlledScenarioSource.parse(mapper.writeValueAsString(thin),new HashSet<>(selectedIds),"RETRY_AFTER_RESPONSE_LOSS",3);
                var queued=tx.execute(status->queue.enqueueGenerated(owner,spec,120));UUID job=(UUID)queued.get("executionId");
                var claim=tx.execute(status->queue.claim(new QueueContracts.Claim("diagnosis-fake-worker",snapshot)));
                var metadata=new LinkedHashMap<String,Object>();metadata.put("executionId",claim.get("executionId").toString());metadata.put("snapshotId",snapshot.toString());metadata.put("operation","RUN_SELECTED_TEST");metadata.put("selectedTest",GeneratedReproduction.SELECTOR);metadata.put("snapshotContentHash",hash);
                metadata.put("state","FAILED");metadata.put("classification","ASSERTION_FAILED");metadata.put("exitCode",1);metadata.put("snapshotVerifiedAfter",true);
                metadata.put("generatedTest",Map.of("installedSourceSha256",ContextDocuments.hash(spec.testSource()),"unchangedAfterRun",true));metadata.put("stages",List.of(Map.of("goals",List.of("test-compile"),"exitCode",0)));
                metadata.put("junit",Map.of("tests",1,"failures",1,"errors",0,"skipped",0,"details",List.of(Map.of("className",GeneratedReproduction.SELECTOR,"testName","duplicateChargeOnRetry","type","org.opentest4j.AssertionFailedError","message","BUGTRACE_SCENARIO_DUPLICATES ==> expected: <0> but was: <3>"))));
                String stdout=ControlledScenarioReport.PREFIX+mapper.writeValueAsString(Map.of("scenario","RETRY_AFTER_RESPONSE_LOSS","repetitions",3,"completed",3,"duplicates",3,"passes",0,"errors",0,"clientTimeouts",0,"chargeCounts",List.of(2,2,2)));
                tx.execute(status->queue.complete(job,new QueueContracts.Complete((UUID)claim.get("leaseToken"),"diagnosis-fake-worker",metadata,stdout,"")));
                UUID id=UUID.randomUUID();var input=new DiagnosisService.Create(id,refs,List.of(job));
                var report=diagnosis.create(owner,input);assertEquals("VALIDATED_ADVISORY",report.get("state"));assertEquals(false,report.get("changesAuthorized"));
                assertEquals("VALID",diagnosis.verify(owner,id).get("verification_state"));
                diagnosis.create(owner,input);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->diagnosis.create(other,new DiagnosisService.Create(UUID.randomUUID(),refs,List.of(job))));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->diagnosis.get(other,id));
                var restarted=new DiagnosisService(jdbc,manager,calls,evidence,settings,imports);
                assertEquals("VALID",restarted.verify(owner,id).get("verification_state"));assertEquals(true,restarted.citation(owner,id,0).get("verified"));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                jdbc.update("UPDATE execution_jobs SET stdout=stdout||'changed' WHERE id=?",job);
                assertEquals("INVALID",diagnosis.verify(owner,id).get("verification_state"));
                jdbc.update("UPDATE execution_jobs SET stdout=? WHERE id=?",stdout,job);assertEquals("VALID",diagnosis.verify(owner,id).get("verification_state"));
                jdbc.update("UPDATE diagnosis_reports SET document=jsonb_set(document,'{diagnosis}',CAST(? AS jsonb)) WHERE id=?","\"tampered\"",id);
                assertNull(diagnosis.get(owner,id).get("document"));assertEquals(false,diagnosis.get(owner,id).get("changesAuthorized"));assertEquals("INVALID",diagnosis.verify(owner,id).get("verification_state"));
                jdbc.update("UPDATE diagnosis_reports SET document=CAST(? AS jsonb) WHERE id=?",mapper.writeValueAsString(report.get("document")),id);assertEquals("VALID",diagnosis.verify(owner,id).get("verification_state"));
                AiProvider forbidden=request->{var response=new MockAiProvider().generate(request);return new AiProvider.Response("mock-v1","confidence-test",response.text().replaceFirst("\\{","{\"confidence\":99,"),new AiProvider.Usage(null,null,null,"MOCK_NOT_MEASURED"),false);};
                var badCalls=new AiCallService(jdbc,evidence,forbidden,settings);var badService=new DiagnosisService(jdbc,manager,badCalls,evidence,settings,imports);
                var invalid=badService.create(owner,new DiagnosisService.Create(UUID.randomUUID(),refs,List.of(job)));
                assertEquals("INVALID_OUTPUT",invalid.get("state"));assertEquals(false,invalid.get("changesAuthorized"));assertNull(invalid.get("document"));
                UUID last=null;
                for(int i=0;i<3;i++){last=UUID.randomUUID();assertEquals("VALIDATED_ADVISORY",diagnosis.create(owner,new DiagnosisService.Create(last,refs,List.of(job))).get("state"));}
                assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->diagnosis.create(owner,new DiagnosisService.Create(UUID.randomUUID(),refs,List.of(job))));
                assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                jdbc.update("UPDATE diagnosis_reports SET state='GENERATING' WHERE id=?",last);diagnosis.recover();
                assertEquals("INTERRUPTED",diagnosis.get(owner,last).get("state"));assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                assertEquals(hash,GitSnapshot.contentHash(base));
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
}
