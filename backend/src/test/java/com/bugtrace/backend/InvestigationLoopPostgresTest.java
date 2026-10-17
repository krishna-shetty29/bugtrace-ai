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

@EnabledIfEnvironmentVariable(named="RUN_LOOP_PG_TEST",matches="true")
class InvestigationLoopPostgresTest {
    @TempDir Path workspace;
    static final class Embeddings extends OllamaEmbeddings {
        Embeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return "a".repeat(64);}
        @Override public List<String> embed(List<String> input,Duration timeout){return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();}
    }
    @Test void savesBoundedLoopUsesActualResultsAndFencesExpiredJobs() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")),schema="agent_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){assertTrue(row.next());assertEquals(1,row.getInt(1),"Complete Step 25 database setup first");}
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);var jdbc=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql","V9__add_model_calls.sql","V10__add_bounded_agent_roles.sql","V11__add_role_tool_gateway.sql","V12__add_generated_reproduction.sql","V13__add_bounded_investigation_loop.sql","V14__allow_unstarted_execution_errors.sql","V15__add_controlled_scenario_batches.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge() {}\n}\n");Files.writeString(base.resolve("README.md"),"# Checkout\nRetry after a lost response.\n");Files.createDirectories(base.resolve("src/test/java/demo"));Files.writeString(base.resolve("src/test/java/demo/RetryTest.java"),"package demo; class RetryTest {}\n");String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"AI integration test");jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash,"fixtures/checkout-timeout");
                for(UUID id:List.of(owner,other))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",id,project,snapshot,"AI test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),workspace.toString(),workspace.toString());var embeds=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);var semantic=new SemanticRetrievalService(jdbc,imports,search,graphs,embeds,manager);semantic.build(owner);
                var documents=new DocumentContextService(jdbc,imports,semantic,embeds,manager,workspace.toString());documents.build(owner,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));var evidence=new AiEvidenceService(jdbc,imports,documents);var choices=evidence.available(owner);assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CODE")));assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CONTEXT")));
                var refs=choices.stream().limit(8).map(AiEvidenceService.Evidence::reference).toList();
                var settings=new AiSettings("mock","mock-v1","",60,1500);var calls=new AiCallService(jdbc,evidence,new MockAiProvider(),settings);
                var queue=new DurableQueueService(jdbc);var tx=new TransactionTemplate(manager);
                var loop=new InvestigationLoopService(jdbc,manager,calls,evidence,settings,imports,search,queue);
                UUID id=UUID.randomUUID();var limits=new InvestigationLoopPolicy.Limits(2,3,8,12000,900,300,1800,2);
                var create=new InvestigationLoopService.Create(id,refs,"charge",limits);
                loop.create(owner,create);loop.create(owner,create);
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->loop.get(other,id));
                var request=new InvestigationLoopService.Step(UUID.randomUUID(),0,"ADVANCE");
                assertEquals("PLAN_READY",loop.step(owner,id,request).get("state"));
                loop.step(owner,id,request);assertEquals(1,jdbc.queryForObject("SELECT calls FROM investigation_loops WHERE id=?",Integer.class,id));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->loop.step(owner,id,new InvestigationLoopService.Step(UUID.randomUUID(),0,"ADVANCE")));
                advance(loop,owner,id,"ADVANCE");assertEquals("PLAN_VALIDATED",loop.get(owner,id).get("state"));
                advance(loop,owner,id,"ADVANCE");assertEquals("DRAFT_READY",loop.get(owner,id).get("state"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->advance(loop,owner,id,"ADVANCE"));
                advance(loop,owner,id,"ENABLE");advance(loop,owner,id,"ADVANCE");
                assertEquals("WAITING",loop.get(owner,id).get("state"));
                UUID job=jdbc.queryForObject("SELECT execution_id FROM investigation_loop_rounds WHERE loop_id=?",UUID.class,id);
                assertEquals(1,jdbc.queryForObject("SELECT max_attempts FROM execution_jobs WHERE id=?",Integer.class,job));
                assertEquals(300,jdbc.queryForObject("SELECT reserved_execution_seconds FROM investigation_loops WHERE id=?",Integer.class,id));
                var claimed=tx.execute(status->queue.claim(new QueueContracts.Claim("loop-fake-worker",snapshot)));
                assertNotNull(claimed.get("notAfter"));assertNotNull(claimed.get("generatedTest"));
                var metadata=new LinkedHashMap<String,Object>();metadata.put("executionId",claimed.get("executionId").toString());metadata.put("snapshotId",snapshot.toString());metadata.put("operation","RUN_SELECTED_TEST");metadata.put("selectedTest",GeneratedReproduction.SELECTOR);metadata.put("snapshotContentHash",hash);
                metadata.put("state","FAILED");metadata.put("classification","ASSERTION_FAILED");metadata.put("exitCode",1);metadata.put("snapshotVerifiedAfter",true);
                metadata.put("notAfter",claimed.get("notAfter"));
                String sha=jdbc.queryForObject("SELECT source_sha FROM investigation_loop_rounds WHERE loop_id=?",String.class,id);
                metadata.put("generatedTest",Map.of("installedSourceSha256",sha,"unchangedAfterRun",true));
                metadata.put("stages",List.of(Map.of("goals",List.of("test-compile"),"exitCode",0)));
                metadata.put("junit",Map.of("tests",1,"failures",1,"errors",0,"skipped",0,"details",List.of(Map.of("className",GeneratedReproduction.SELECTOR,"testName","duplicateChargeOnRetry","type","org.opentest4j.AssertionFailedError","message",GeneratedReproduction.SIGNATURE))));
                tx.execute(status->queue.complete(job,new QueueContracts.Complete((UUID)claimed.get("leaseToken"),"loop-fake-worker",metadata,"Controlled fake JUnit result; no repository code executed","")));
                assertEquals("RESULT_READY",loop.get(owner,id).get("state"));
                int count=jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class);
                for(int i=0;i<3;i++)loop.get(owner,id);
                assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                advance(loop,owner,id,"ADVANCE");assertEquals("FINISHED_REPRODUCED",loop.get(owner,id).get("state"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->advance(loop,owner,id,"ADVANCE"));
                assertEquals("FINISHED_REPRODUCED",new InvestigationLoopService(jdbc,manager,calls,evidence,settings,imports,search,queue).get(owner,id).get("state"));
                assertEquals(hash,GitSnapshot.contentHash(base));assertFalse(Files.exists(base.resolve(GeneratedReproduction.PATH)));
                // A one-call budget cannot pay for test generation after planning.
                UUID limited=UUID.randomUUID();loop.create(owner,new InvestigationLoopService.Create(limited,refs,"charge",new InvestigationLoopPolicy.Limits(1,1,1,1500,300,300,1800,0)));
                advance(loop,owner,limited,"ADVANCE");advance(loop,owner,limited,"ADVANCE");advance(loop,owner,limited,"ADVANCE");
                assertEquals("BUDGET_EXHAUSTED",loop.get(owner,limited).get("state"));
                assertEquals(1,jdbc.queryForObject("SELECT calls FROM investigation_loops WHERE id=?",Integer.class,limited));
                // Expired queued jobs are never handed to a worker.
                var expired=tx.execute(status->queue.enqueueGenerated(owner,new GeneratedReproduction.Spec(GeneratedReproduction.exampleSource(),GeneratedReproduction.ASSERTION,GeneratedReproduction.FAULT,List.of(choices.getFirst().id()),GeneratedReproduction.SIGNATURE,"Controlled test"),120));
                UUID expiredId=(UUID)expired.get("executionId");jdbc.update("UPDATE execution_jobs SET not_after=clock_timestamp()-INTERVAL '1 second' WHERE id=?",expiredId);
                assertNull(tx.execute(status->queue.claim(new QueueContracts.Claim("loop-fake-worker",snapshot))));
                assertEquals("FAILED",queue.view(expiredId).get("queueState"));
                assertEquals("QUEUE_DEADLINE_EXPIRED",queue.view(expiredId).get("technicalError"));
                assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT started_at IS NULL AND finished_at IS NOT NULL AND finished_at>=created_at FROM executions WHERE id=?",Boolean.class,expiredId)),"A queued expiry records termination without inventing a start time");
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE executions SET execution_state='FINISHED' WHERE id=?",expiredId),"A successful execution must still have a start time");
                // The loop itself enforces two repairs, preserves versions, and reserves all job time.
                var generations=new AtomicInteger();
                AiProvider brokenProvider=modelRequest->{
                    var response=new MockAiProvider().generate(modelRequest);
                    if(!modelRequest.instructions().equals(GeneratedReproduction.INSTRUCTIONS))return response;
                    var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
                    var node=(tools.jackson.databind.node.ObjectNode)mapper.readTree(response.text());
                    node.put("testSource",GeneratedReproduction.exampleSource().replace("ledger, 0, true","ledger, 0, true, missingArgument"+generations.incrementAndGet()));
                    return new AiProvider.Response("mock-v1","fake-compiler-model",mapper.writeValueAsString(node),new AiProvider.Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
                };
                var brokenCalls=new AiCallService(jdbc,evidence,brokenProvider,settings);
                var repairLoop=new InvestigationLoopService(jdbc,manager,brokenCalls,evidence,settings,imports,search,queue);
                UUID repairing=UUID.randomUUID();repairLoop.create(owner,new InvestigationLoopService.Create(repairing,refs,"charge",limits));
                advance(repairLoop,owner,repairing,"ADVANCE");advance(repairLoop,owner,repairing,"ADVANCE");advance(repairLoop,owner,repairing,"ADVANCE");advance(repairLoop,owner,repairing,"ENABLE");
                for(int i=0;i<3;i++) {
                    advance(repairLoop,owner,repairing,"ADVANCE");
                    var attempt=tx.execute(status->queue.claim(new QueueContracts.Claim("loop-fake-worker",snapshot)));
                    var repairMeta=new LinkedHashMap<String,Object>(metadata);
                    repairMeta.put("executionId",attempt.get("executionId").toString());repairMeta.put("notAfter",attempt.get("notAfter"));
                    repairMeta.put("classification","COMPILATION_FAILED");repairMeta.remove("junit");
                    String repairSha=jdbc.queryForObject("SELECT source_sha FROM investigation_loop_rounds WHERE loop_id=?",String.class,repairing);
                    repairMeta.put("generatedTest",Map.of("installedSourceSha256",repairSha,"unchangedAfterRun",true));
                    repairMeta.put("stages",List.of(Map.of("goals",List.of("test-compile"),"exitCode",1)));
                    tx.execute(status->queue.complete((UUID)attempt.get("jobId"),new QueueContracts.Complete((UUID)attempt.get("leaseToken"),"loop-fake-worker",repairMeta,"Controlled fake compiler error: "+GeneratedReproduction.CLASS+".java missing argument","")));
                    assertEquals(i<2?"COMPILE_ERROR":"REPAIR_EXHAUSTED",repairLoop.get(owner,repairing).get("state"));
                    if(i<2)advance(repairLoop,owner,repairing,"ADVANCE");
                }
                assertEquals(3,generations.get());assertEquals(900,jdbc.queryForObject("SELECT reserved_execution_seconds FROM investigation_loops WHERE id=?",Integer.class,repairing));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->advance(repairLoop,owner,repairing,"ADVANCE"));
                assertEquals(3,jdbc.queryForObject("SELECT count(DISTINCT document->>'testSource') FROM investigation_loop_steps WHERE loop_id=? AND action IN ('GENERATE_TEST','REPAIR_TEST')",Integer.class,repairing));
                // Crash recovery never resends a model call.
                UUID interrupted=UUID.randomUUID();loop.create(owner,new InvestigationLoopService.Create(interrupted,refs,"charge",limits));
                jdbc.update("UPDATE investigation_loops SET state='BUSY' WHERE id=?",interrupted);
                count=jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class);loop.recover();
                assertEquals("INTERRUPTED",loop.get(owner,interrupted).get("state"));
                assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM ai_model_calls",Integer.class));
                // Controlled batches traverse generation, queueing, report parsing, persistence and review.
                for(String scenario:ControlledScenarioSource.SCENARIOS) {
                    UUID batch=UUID.randomUUID();loop.create(owner,new InvestigationLoopService.Create(batch,refs,"charge",limits,scenario,3));
                    advance(loop,owner,batch,"ADVANCE");advance(loop,owner,batch,"ADVANCE");advance(loop,owner,batch,"ADVANCE");
                    assertEquals("DRAFT_READY",loop.get(owner,batch).get("state"));
                    advance(loop,owner,batch,"ENABLE");advance(loop,owner,batch,"ADVANCE");
                    var controlled=tx.execute(status->queue.claim(new QueueContracts.Claim("loop-fake-worker",snapshot)));
                    var batchMetadata=new LinkedHashMap<String,Object>(metadata);
                    batchMetadata.put("executionId",controlled.get("executionId").toString());batchMetadata.put("notAfter",controlled.get("notAfter"));
                    String batchHash=jdbc.queryForObject("SELECT source_sha FROM investigation_loop_rounds WHERE loop_id=?",String.class,batch);
                    batchMetadata.put("generatedTest",Map.of("installedSourceSha256",batchHash,"unchangedAfterRun",true));
                    batchMetadata.put("junit",Map.of("tests",1,"failures",1,"errors",0,"skipped",0,"details",List.of(Map.of("className",GeneratedReproduction.SELECTOR,"testName","duplicateChargeOnRetry","type","org.opentest4j.AssertionFailedError","message","BUGTRACE_SCENARIO_DUPLICATES ==> expected: <0> but was: <3>"))));
                    var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
                    String report=ControlledScenarioReport.PREFIX+mapper.writeValueAsString(Map.of("scenario",scenario,"repetitions",3,"completed",3,"duplicates",3,"passes",0,"errors",0,"clientTimeouts",scenario.equals("DELAYED_RESPONSE")?3:0,"chargeCounts",Collections.nCopies(3,scenario.equals("CONCURRENT_RETRIES")?3:2)));
                    tx.execute(status->queue.complete((UUID)controlled.get("jobId"),new QueueContracts.Complete((UUID)controlled.get("leaseToken"),"loop-fake-worker",batchMetadata,report,"")));
                    assertEquals("RESULT_READY",loop.get(owner,batch).get("state"));
                    var saved=mapper.readTree(jdbc.queryForObject("SELECT scenario_report::text FROM investigation_loop_rounds WHERE loop_id=?",String.class,batch));
                    assertEquals(3,saved.path("duplicates").asInt());assertEquals(1.0,saved.path("duplicateRate").asDouble());
                    advance(loop,owner,batch,"ADVANCE");assertEquals("FINISHED_REPRODUCED",loop.get(owner,batch).get("state"));
                    assertEquals(hash,GitSnapshot.contentHash(base));
                }
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
    private static Map<String,Object> advance(InvestigationLoopService service,UUID investigation,UUID id,String command) {
        var saved=service.get(investigation,id);
        return service.step(investigation,id,new InvestigationLoopService.Step(UUID.randomUUID(),((Number)saved.get("version")).intValue(),command));
    }
}
