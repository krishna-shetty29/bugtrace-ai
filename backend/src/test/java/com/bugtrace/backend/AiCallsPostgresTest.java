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

@EnabledIfEnvironmentVariable(named="RUN_AI_PG_TEST",matches="true")
class AiCallsPostgresTest {
    @TempDir Path workspace;
    static final class Embeddings extends OllamaEmbeddings {
        Embeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return "a".repeat(64);}
        @Override public List<String> embed(List<String> input,Duration timeout){return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();}
    }
    @Test void storesScopedEvidenceMockUsageFailuresAndReplayWithoutLiveCalls() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD")),schema="ai_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){assertTrue(row.next());assertEquals(1,row.getInt(1),"Complete Step 25 database setup first");}
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);var jdbc=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql","V9__add_model_calls.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge() {}\n}\n");Files.writeString(base.resolve("README.md"),"# Checkout\nRetry after a lost response.\n");String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"AI integration test");jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash,"fixture");
                for(UUID id:List.of(owner,other))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",id,project,snapshot,"AI test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),workspace.toString(),workspace.toString());var embeds=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);var semantic=new SemanticRetrievalService(jdbc,imports,search,graphs,embeds,manager);semantic.build(owner);
                var documents=new DocumentContextService(jdbc,imports,semantic,embeds,manager,workspace.toString());documents.build(owner,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));var evidence=new AiEvidenceService(jdbc,imports,documents);var choices=evidence.available(owner);assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CODE")));assertTrue(choices.stream().anyMatch(item->item.reference().kind().equals("CONTEXT")));
                var refs=choices.stream().limit(2).map(AiEvidenceService.Evidence::reference).toList();var count=new AtomicInteger();AiProvider mock=request->{count.incrementAndGet();return new MockAiProvider().generate(request);};String key="sk-test-abcdefghijklmnop";var settings=new AiSettings("mock","mock-v1",key,60,1500);var service=new AiCallService(jdbc,evidence,mock,settings);UUID call=UUID.randomUUID();var input=new AiCallService.Submission(call,"Summarize the selected evidence",refs);
                var result=service.submit(owner,input);assertEquals("SUCCEEDED",result.get("state"));assertEquals("MOCK_NOT_MEASURED",result.get("usage_kind"));assertNull(result.get("total_tokens"));assertEquals(AiCallService.PROMPT_VERSION,result.get("prompt_version"));assertTrue(result.get("input_evidence").toString().contains("sourceHash"));assertNotNull(result.get("duration_ms"));
                assertEquals(call,service.submit(owner,input).get("id"));assertEquals(1,count.get());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.submit(owner,new AiCallService.Submission(call,"Changed question",refs)));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.get(other,call));
                var context=choices.stream().filter(item->item.reference().kind().equals("CONTEXT")).findFirst().orElseThrow();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.submit(other,new AiCallService.Submission(UUID.randomUUID(),"wrong report",List.of(context.reference()))));
                var failure=new AiCallService(jdbc,evidence,request->{throw new AiProvider.Failure("AUTHENTICATION_ERROR","Controlled failure "+key,"req_test");},settings);
                var failed=failure.submit(owner,new AiCallService.Submission(UUID.randomUUID(),"failure test",refs));assertEquals("FAILED",failed.get("state"));assertEquals("AUTHENTICATION_ERROR",failed.get("error_code"));assertFalse(failed.toString().contains(key));assertNull(failed.get("total_tokens"));
                var partial=new AiCallService(jdbc,evidence,request->new AiProvider.Response("returned-model","req_test","partial "+key,new AiProvider.Usage(10L,3L,13L,"MEASURED"),true),settings);
                var incomplete=partial.submit(owner,new AiCallService.Submission(UUID.randomUUID(),"partial test",refs));assertEquals("INCOMPLETE",incomplete.get("state"));assertEquals(13L,incomplete.get("total_tokens"));assertEquals("returned-model",incomplete.get("actual_model"));assertFalse(incomplete.toString().contains(key));
                jdbc.update("UPDATE ai_model_calls SET state='STARTED' WHERE id=?",failed.get("id"));service.recoverInterruptedCalls();assertEquals("BACKEND_RESTART",service.get(owner,(UUID)failed.get("id")).get("error_code"));assertEquals(1,count.get());
                // A new service instance reads the same saved record; model execution is not required.
                assertEquals("SUCCEEDED",new AiCallService(jdbc,evidence,mock,settings).get(owner,call).get("state"));assertEquals(hash,GitSnapshot.contentHash(base));
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
}
