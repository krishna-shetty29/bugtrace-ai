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

@EnabledIfEnvironmentVariable(named="RUN_AGENT_PG_TEST",matches="true")
class AgentWorkflowPostgresTest {
    @TempDir Path workspace;
    static final class Embeddings extends OllamaEmbeddings {
        Embeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return "a".repeat(64);}
        @Override public List<String> embed(List<String> input,Duration timeout){return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();}
    }
    @Test void coordinatesSequentialRolesBudgetsAndRestartWithoutRealModelCalls() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")),schema="agent_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){assertTrue(row.next());assertEquals(1,row.getInt(1),"Complete Step 25 database setup first");}
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);var jdbc=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql","V9__add_model_calls.sql","V10__add_bounded_agent_roles.sql","V11__add_role_tool_gateway.sql","V12__add_generated_reproduction.sql","V13__add_bounded_investigation_loop.sql","V14__allow_unstarted_execution_errors.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge() {}\n}\n");Files.writeString(base.resolve("README.md"),"# Checkout\nRetry after a lost response.\n");Files.createDirectories(base.resolve("src/test/java/demo"));Files.writeString(base.resolve("src/test/java/demo/RetryTest.java"),"package demo; class RetryTest {}\n");String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"AI integration test");jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash,"fixtures/checkout-timeout");
                for(UUID id:List.of(owner,other))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",id,project,snapshot,"AI test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),workspace.toString(),workspace.toString());var embeds=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);var semantic=new SemanticRetrievalService(jdbc,imports,search,graphs,embeds,manager);semantic.build(owner);
                var documents=new DocumentContextService(jdbc,imports,semantic,embeds,manager,workspace.toString());documents.build(owner,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));var evidence=new AiEvidenceService(jdbc,imports,documents);var choices=evidence.available(owner);assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CODE")));assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CONTEXT")));
                var refs=choices.stream().limit(8).map(AiEvidenceService.Evidence::reference).toList();
                var count=new AtomicInteger();
                var entered=new java.util.concurrent.CountDownLatch(1);
                var release=new java.util.concurrent.CountDownLatch(1);
                AiProvider mock=request->{
                    if(count.incrementAndGet()==1){entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AiProvider.Failure("TEST_TIMEOUT","Controlled test timeout",null);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AiProvider.Failure("INTERRUPTED","Test interrupted",null);}}
                    return new MockAiProvider().generate(request);
                };
                var settings=new AiSettings("mock","mock-v1","",60,1500);
                var calls=new AiCallService(jdbc,evidence,mock,settings);
                var gateway=new RoleToolGateway(jdbc,manager,evidence,imports,new DurableQueueService(jdbc),settings);
                var service=new AgentWorkflowService(jdbc,calls,evidence,settings,manager,gateway);
                UUID run=UUID.randomUUID();
                var create=new AgentWorkflowService.Create(run,refs,List.of(),5,7500,1800);
                service.create(owner,create);
                assertEquals(run,service.create(owner,create).get("id"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.get(other,run));
                var json=tools.jackson.databind.json.JsonMapper.builder().build();
                UUID read=UUID.randomUUID();
                var readBody=json.readTree(json.writeValueAsString(Map.of("requestId",read,"expectedRole",0,"tool","searchCode","arguments",Map.of("query","charge"))));
                assertEquals("SUCCEEDED",gateway.manual(owner,run,readBody).get("state"));
                assertEquals("SUCCEEDED",gateway.manual(owner,run,readBody).get("state"));
                assertEquals(1,gateway.list(owner,run).size());
                var denied=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"expectedRole",0,"tool","proposePatch","arguments",Map.of("diff","do not apply"))));
                assertEquals("DENIED",gateway.manual(owner,run,denied).get("state"));
                assertEquals(2,gateway.list(owner,run).size());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->gateway.list(other,run));
                try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
                    var first=executor.submit(()->service.advance(owner,run,0));
                    assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                    try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.advance(owner,run,0));}
                    finally{release.countDown();}
                    assertEquals("READY",first.get(10,java.util.concurrent.TimeUnit.SECONDS).get("state"));
                }
                assertEquals(1,count.get());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.advance(owner,run,0));
                UUID draft=UUID.randomUUID();
                var draftBody=json.readTree(json.writeValueAsString(Map.of("requestId",draft,"expectedRole",1,"tool","createTest","arguments",Map.of("path","src/test/java/demo/NewRetryTest.java","source","package demo; class NewRetryTest {}"))));
                assertEquals("SUCCEEDED",gateway.manual(owner,run,draftBody).get("state"));
                assertFalse(Files.exists(base.resolve("src/test/java/demo/NewRetryTest.java")));
                var jobArgs=Map.of("operation","RUN_SELECTED_TEST","testSelector","RetryTest","timeoutSeconds",120);
                var disabledBody=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"expectedRole",1,"tool","requestExperiment","arguments",jobArgs)));
                assertEquals("DENIED",gateway.manual(owner,run,disabledBody).get("state"));
                gateway.permissions(owner,run,true);
                UUID jobRequest=UUID.randomUUID();
                var jobBody=json.readTree(json.writeValueAsString(Map.of("requestId",jobRequest,"expectedRole",1,"tool","requestExperiment","arguments",jobArgs)));
                var queued=gateway.manual(owner,run,jobBody);assertEquals("SUCCEEDED",queued.get("state"));
                UUID jobId=(UUID)queued.get("execution_id");assertNotNull(jobId);
                assertEquals(jobId,gateway.manual(owner,run,jobBody).get("execution_id"));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM execution_jobs",Integer.class));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.advance(owner,run,1));
                var queue=new DurableQueueService(jdbc);
                var claimed=new TransactionTemplate(manager).execute(tx->queue.claim(new QueueContracts.Claim("controlled-test-worker",snapshot)));
                var metadata=new LinkedHashMap<String,Object>();metadata.put("executionId",claimed.get("executionId").toString());metadata.put("snapshotId",snapshot.toString());
                metadata.put("operation","RUN_SELECTED_TEST");metadata.put("selectedTest","RetryTest");metadata.put("snapshotContentHash",hash);
                metadata.put("state","FAILED");metadata.put("classification","ASSERTION_FAILED");metadata.put("exitCode",1);
                new TransactionTemplate(manager).execute(tx->queue.complete(jobId,new QueueContracts.Complete((UUID)claimed.get("leaseToken"),"controlled-test-worker",metadata,"controlled fake worker output; no actual code was run","")));
                for(int index=1;index<4;index++)service.advance(owner,run,index);
                var checkBody=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"expectedRole",4,"tool","requestApprovedChecks","arguments",jobArgs)));
                var verificationQueued=gateway.manual(owner,run,checkBody);assertEquals("SUCCEEDED",verificationQueued.get("state"));
                UUID verificationJob=(UUID)verificationQueued.get("execution_id");
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.advance(owner,run,4));
                var verificationClaim=new TransactionTemplate(manager).execute(tx->queue.claim(new QueueContracts.Claim("controlled-test-worker",snapshot)));
                metadata.put("executionId",verificationClaim.get("executionId").toString());
                new TransactionTemplate(manager).execute(tx->queue.complete(verificationJob,new QueueContracts.Complete((UUID)verificationClaim.get("leaseToken"),"controlled-test-worker",metadata,"controlled fake verification output; no code was run","")));
                service.advance(owner,run,4);
                assertEquals("TOOL_REVIEW",service.get(owner,run).get("state"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.finishVerification(owner,run));
                var summaryBody=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"expectedRole",4,"tool","createVerificationSummary","arguments",Map.of("executionIds",List.of(verificationJob)))));
                assertEquals("SUCCEEDED",gateway.manual(owner,run,summaryBody).get("state"));
                service.finishVerification(owner,run);
                assertEquals("MOCK_COMPLETE",service.get(owner,run).get("state"));
                assertEquals(5,count.get());
                assertEquals(7500,((Number)service.get(owner,run).get("reserved_output_tokens")).intValue());
                assertEquals("MOCK_COMPLETE",new AgentWorkflowService(jdbc,calls,evidence,settings,manager,gateway).get(owner,run).get("state"));
                assertEquals(5,count.get());
                UUID limited=UUID.randomUUID();
                service.create(owner,new AgentWorkflowService.Create(limited,refs,List.of(),1,1500,1800));
                service.advance(owner,limited,0);
                service.advance(owner,limited,1);
                assertEquals("BUDGET_EXHAUSTED",service.get(owner,limited).get("state"));
                assertEquals(6,count.get());
                UUID idle=UUID.randomUUID();
                service.create(owner,new AgentWorkflowService.Create(idle,refs,List.of(),5,7500,1800));
                assertEquals("INTERRUPTED",service.stop(owner,idle).get("state"));
                UUID crashed=UUID.randomUUID();
                service.create(owner,new AgentWorkflowService.Create(crashed,refs,List.of(),5,7500,1800));
                jdbc.update("UPDATE agent_runs SET state='RUNNING' WHERE id=?",crashed);
                service.recover();
                assertEquals("INTERRUPTED",service.get(owner,crashed).get("state"));
                assertEquals(6,count.get());
                assertEquals(hash,GitSnapshot.contentHash(base));
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
}
