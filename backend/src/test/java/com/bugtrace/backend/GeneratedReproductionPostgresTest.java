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

@EnabledIfEnvironmentVariable(named="RUN_GENERATED_PG_TEST",matches="true")
class GeneratedReproductionPostgresTest {
    @TempDir Path workspace;
    static final class Embeddings extends OllamaEmbeddings {
        Embeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return "a".repeat(64);}
        @Override public List<String> embed(List<String> input,Duration timeout){return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();}
    }
    @Test void boundsGeneratedCompilationRepairsAndKeepsBaseImmutable() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")),schema="agent_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){assertTrue(row.next());assertEquals(1,row.getInt(1),"Complete Step 25 database setup first");}
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);var jdbc=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql","V9__add_model_calls.sql","V10__add_bounded_agent_roles.sql","V11__add_role_tool_gateway.sql","V12__add_generated_reproduction.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge() {}\n}\n");Files.writeString(base.resolve("README.md"),"# Checkout\nRetry after a lost response.\n");Files.createDirectories(base.resolve("src/test/java/demo"));Files.writeString(base.resolve("src/test/java/demo/RetryTest.java"),"package demo; class RetryTest {}\n");String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"AI integration test");jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash,"fixtures/checkout-timeout");
                for(UUID id:List.of(owner,other))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",id,project,snapshot,"AI test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),workspace.toString(),workspace.toString());var embeds=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);var semantic=new SemanticRetrievalService(jdbc,imports,search,graphs,embeds,manager);semantic.build(owner);
                var documents=new DocumentContextService(jdbc,imports,semantic,embeds,manager,workspace.toString());documents.build(owner,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));var evidence=new AiEvidenceService(jdbc,imports,documents);var choices=evidence.available(owner);assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CODE")));assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CONTEXT")));
                var refs=choices.stream().limit(8).map(AiEvidenceService.Evidence::reference).toList();
                var json=tools.jackson.databind.json.JsonMapper.builder().build();var generations=new AtomicInteger();
                AiProvider provider=request->{
                    var response=new MockAiProvider().generate(request);
                    if(!request.instructions().equals(GeneratedReproduction.INSTRUCTIONS))return response;
                    var node=(tools.jackson.databind.node.ObjectNode)json.readTree(response.text());
                    int number=generations.incrementAndGet();
                    node.put("testSource",GeneratedReproduction.exampleSource().replace("ledger, 0, true","ledger, 0, true, unknownArgument"+number));
                    return new AiProvider.Response("mock-v1","scripted-compiler-repair",json.writeValueAsString(node),new AiProvider.Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
                };
                var settings=new AiSettings("mock","mock-v1","",60,1500);var calls=new AiCallService(jdbc,evidence,provider,settings);
                var queue=new DurableQueueService(jdbc);var gateway=new RoleToolGateway(jdbc,manager,evidence,imports,queue,settings);
                var workflow=new AgentWorkflowService(jdbc,calls,evidence,settings,manager,gateway);
                var reproduction=new ReproductionService(jdbc,manager,calls,settings,queue);
                UUID parent=UUID.randomUUID(),request=UUID.randomUUID();workflow.create(owner,new AgentWorkflowService.Create(parent,refs,List.of(),8,12000,1800));
                workflow.advance(owner,parent,0);gateway.permissions(owner,parent,true);
                reproduction.generate(owner,parent,new ReproductionService.Generate(request,2,-1),false);
                assertEquals("DRAFT_READY",reproduction.get(owner,parent).get("state"));
                for(int attempt=0;attempt<3;attempt++) {
                    var queued=reproduction.execute(owner,parent,new ReproductionService.Execute(attempt,120));
                    var records=jdbc.queryForList("SELECT * FROM reproduction_attempts WHERE reproduction_id=? ORDER BY attempt_number",request);
                    var record=records.getLast();UUID job=(UUID)record.get("execution_id");assertNotNull(job);
                    reproduction.execute(owner,parent,new ReproductionService.Execute(attempt,120));
                    assertEquals(attempt+1,jdbc.queryForObject("SELECT count(*) FROM execution_jobs",Integer.class));
                    var claimed=new TransactionTemplate(manager).execute(tx->queue.claim(new QueueContracts.Claim("generated-test-fake-worker",snapshot)));
                    assertNotNull(claimed.get("generatedTest"));
                    var metadata=new LinkedHashMap<String,Object>();metadata.put("executionId",claimed.get("executionId").toString());metadata.put("snapshotId",snapshot.toString());metadata.put("operation","RUN_SELECTED_TEST");metadata.put("selectedTest",GeneratedReproduction.SELECTOR);metadata.put("snapshotContentHash",hash);
                    metadata.put("state","FAILED");metadata.put("classification","COMPILATION_FAILED");metadata.put("exitCode",1);metadata.put("snapshotVerifiedAfter",true);
                    metadata.put("generatedTest",Map.of("installedSourceSha256",record.get("source_sha"),"unchangedAfterRun",true));
                    metadata.put("stages",List.of(Map.of("goals",List.of("test-compile"),"exitCode",1)));
                    new TransactionTemplate(manager).execute(tx->queue.complete(job,new QueueContracts.Complete((UUID)claimed.get("leaseToken"),"generated-test-fake-worker",metadata,"Controlled fake compiler diagnostic: "+GeneratedReproduction.CLASS+".java unknown argument; no actual code executed","")));
                    assertEquals(attempt<2?"COMPILE_ERROR":"REPAIR_EXHAUSTED",reproduction.get(owner,parent).get("state"));
                    if(attempt<2)reproduction.generate(owner,parent,new ReproductionService.Generate(request,2,attempt),true);
                }
                assertEquals(3,generations.get());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->reproduction.generate(owner,parent,new ReproductionService.Generate(request,2,2),true));
                assertEquals(3,generations.get());assertEquals(hash,GitSnapshot.contentHash(base));assertFalse(Files.exists(base.resolve(GeneratedReproduction.PATH)));
                for(var row:jdbc.queryForList("SELECT document FROM reproduction_attempts WHERE reproduction_id=?",request))assertEquals(GeneratedReproduction.ASSERTION,json.readTree(row.get("document").toString()).get("intendedAssertion").asString());
                assertEquals("REPAIR_EXHAUSTED",new ReproductionService(jdbc,manager,calls,settings,queue).get(owner,parent).get("state"));
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
}
