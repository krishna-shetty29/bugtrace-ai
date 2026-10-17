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
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql","V9__add_model_calls.sql","V10__add_bounded_agent_roles.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge() {}\n}\n");Files.writeString(base.resolve("README.md"),"# Checkout\nRetry after a lost response.\n");String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"AI integration test");jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash,"fixture");
                for(UUID id:List.of(owner,other))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",id,project,snapshot,"AI test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),workspace.toString(),workspace.toString());var embeds=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);var semantic=new SemanticRetrievalService(jdbc,imports,search,graphs,embeds,manager);semantic.build(owner);
                var documents=new DocumentContextService(jdbc,imports,semantic,embeds,manager,workspace.toString());documents.build(owner,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));var evidence=new AiEvidenceService(jdbc,imports,documents);var choices=evidence.available(owner);assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CODE")));assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CONTEXT")));
                var refs=choices.stream().limit(2).map(AiEvidenceService.Evidence::reference).toList();
                var count=new AtomicInteger();
                var entered=new java.util.concurrent.CountDownLatch(1);
                var release=new java.util.concurrent.CountDownLatch(1);
                AiProvider mock=request->{
                    if(count.incrementAndGet()==1){entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AiProvider.Failure("TEST_TIMEOUT","Controlled test timeout",null);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AiProvider.Failure("INTERRUPTED","Test interrupted",null);}}
                    return new MockAiProvider().generate(request);
                };
                var settings=new AiSettings("mock","mock-v1","",60,1500);
                var calls=new AiCallService(jdbc,evidence,mock,settings);
                var service=new AgentWorkflowService(jdbc,calls,evidence,settings,manager);
                UUID run=UUID.randomUUID();
                var create=new AgentWorkflowService.Create(run,refs,List.of(),5,7500,1800);
                service.create(owner,create);
                assertEquals(run,service.create(owner,create).get("id"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.get(other,run));
                try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
                    var first=executor.submit(()->service.advance(owner,run,0));
                    assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                    try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.advance(owner,run,0));}
                    finally{release.countDown();}
                    assertEquals("READY",first.get(10,java.util.concurrent.TimeUnit.SECONDS).get("state"));
                }
                assertEquals(1,count.get());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.advance(owner,run,0));
                for(int index=1;index<5;index++)service.advance(owner,run,index);
                assertEquals("MOCK_COMPLETE",service.get(owner,run).get("state"));
                assertEquals(5,count.get());
                assertEquals(7500,((Number)service.get(owner,run).get("reserved_output_tokens")).intValue());
                assertEquals("MOCK_COMPLETE",new AgentWorkflowService(jdbc,calls,evidence,settings,manager).get(owner,run).get("state"));
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
