package com.bugtrace.backend;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_CONTEXT_PG_TEST",matches="true")
class DocumentContextPostgresTest {
    @TempDir Path workspace;
    static final class Embeddings extends OllamaEmbeddings {
        int generated;boolean fail;String digest="a".repeat(64);
        Embeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return digest;}
        @Override public List<String> embed(List<String> input,Duration timeout){
            if(fail)throw new IllegalStateException("Controlled embedding outage");generated+=input.size();
            return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();
        }
    }
    @Test void indexesEvidenceReusesHashesAndSeparatesHistoryAndOtherReports() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="context_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){
                assertTrue(row.next());assertEquals(1,row.getInt(1),"Complete Step 25 database setup first");
            }
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);var jdbc=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql","V8__add_document_history_context.sql"))statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),report=UUID.randomUUID(),outsiderSnapshot=UUID.randomUUID(),outsider=UUID.randomUUID(),job=UUID.randomUUID();
                Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);
                Files.writeString(base.resolve("README.md"),"# Payment\nRetry without another charge.\n## Persistence\nThe fake ledger is in memory.\n");
                Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n void charge(){}\n}\n");String hash=GitSnapshot.contentHash(base);
                Path repo=workspace.resolve("repository");Files.createDirectory(repo);SelectedHistory.run(repo,"init");SelectedHistory.run(repo,"config","user.name","Context test");SelectedHistory.run(repo,"config","user.email","test@example.invalid");SelectedHistory.run(repo,"config","commit.gpgsign","false");Files.createDirectory(repo.resolve("empty-hooks"));SelectedHistory.run(repo,"config","core.hooksPath",repo.resolve("empty-hooks").toString());
                Files.createDirectory(repo.resolve("fixture"));Files.copy(base.resolve("README.md"),repo.resolve("fixture/README.md"));SelectedHistory.run(repo,"add","fixture/README.md");SelectedHistory.run(repo,"commit","-m","Payment retry history");String commit=SelectedHistory.run(repo,"rev-parse","HEAD").strip();
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Context integration");
                for(UUID id:List.of(snapshot,outsiderSnapshot))jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,module_path) VALUES (?,?,?,?,?,?,?)",id,project,repo.toString(),commit,base.toString(),hash,"fixture");
                for(UUID id:List.of(owner,report,outsider))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior,created_at) VALUES (?,?,?,?,?,?,?,clock_timestamp()-CAST(? AS interval))",id,project,id.equals(outsider)?outsiderSnapshot:snapshot,"Report "+id,"Retry charge","one charge","two charges",id.equals(report)?"0 days":"1 day");
                jdbc.update("INSERT INTO executions(id,investigation_id,command,execution_state) VALUES (?,?,?,'FINISHED')",job,report,"mvn test");
                jdbc.update("INSERT INTO execution_jobs(id,snapshot_id,operation,test_selector,timeout_seconds,queue_state,stdout,result) VALUES (?,?,'RUN_SELECTED_TEST','CheckoutIdempotencyTest',120,'COMPLETED',?,CAST(? AS jsonb))",job,snapshot,"JUnit output\nexpected: <1> but was: <2>\n","{\"classification\":\"ASSERTION_FAILED\"}");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(manager),repo.toString(),workspace.toString());var model=new Embeddings();var search=new SnapshotSearchService(jdbc,imports);var graph=new JavaGraphService(jdbc,imports);var primary=new SemanticRetrievalService(jdbc,imports,search,graph,model,manager);
                var service=new DocumentContextService(jdbc,imports,primary,model,manager,repo.toString());new JavaIndexService(jdbc,imports).create(report);graph.create(report);primary.build(report);
                var selection=new DocumentContextService.Selection(List.of("README.md"),List.of(commit),List.of(owner),true);
                service.build(report,selection);int generated=model.generated;service.build(report,selection);assertEquals(generated,model.generated,"Unchanged context must not call the model again");
                var statistics=jdbc.queryForObject("SELECT statistics::text FROM context_indexes WHERE investigation_id=? AND state='COMPLETE' ORDER BY created_at DESC LIMIT 1",String.class,report);
                assertTrue(statistics.contains("\"generatedEmbeddings\": 0"));
                int chunkCount=jdbc.queryForObject("SELECT count(*) FROM context_chunks WHERE index_id=(SELECT id FROM context_indexes WHERE investigation_id=? AND state='COMPLETE' ORDER BY created_at DESC LIMIT 1)",Integer.class,report);
                jdbc.update("UPDATE investigations SET bug_description=? WHERE id=?","Retry charge after response loss",owner);
                service.build(report,selection);assertTrue(model.generated>generated);assertTrue(model.generated-generated<chunkCount,"An edited report should not regenerate unchanged documents/history/logs");
                var result=service.retrieve(report,"charge",true,true,true);assertTrue(result.supplementarySemanticUsed());
                assertTrue(result.supplementary().stream().anyMatch(match->match.reference().origin().equals("HISTORICAL_DIFF")));
                var citation=result.supplementary().getFirst().reference();assertEquals(citation,service.citation(report,citation.indexId(),citation.chunkNumber()));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.citation(owner,citation.indexId(),citation.chunkNumber()));
                var filtered=service.retrieve(report,"charge",false,false,true);assertTrue(filtered.supplementary().stream().noneMatch(match->match.reference().origin().startsWith("HISTORICAL")||match.reference().origin().equals("PREVIOUS_REPORT")));
                var logs=service.retrieve(report,"expected: <1> but was: <2>",false,false,false);assertTrue(logs.supplementary().stream().anyMatch(match->match.reference().sourceRef().equals("execution:"+job+":stdout")&&match.reference().lineKind().equals("LOG_LINE")));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.build(report,new DocumentContextService.Selection(List.of(),List.of(),List.of(outsider),false)));
                // Removing selections excludes them from the next active published collection.
                service.build(report,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));
                assertTrue(service.retrieve(report,"charge",true,true,true).supplementary().stream().allMatch(match->match.reference().origin().equals("SNAPSHOT_DOCUMENT")));
                // A changed model digest regenerates, rather than reusing incompatible vectors.
                model.digest="b".repeat(64);generated=model.generated;service.build(report,new DocumentContextService.Selection(List.of("README.md"),List.of(),List.of(),false));assertTrue(model.generated>generated);
                assertEquals(hash,GitSnapshot.contentHash(base));
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
}
