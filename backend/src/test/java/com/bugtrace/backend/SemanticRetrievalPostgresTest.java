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

/** Opt-in. Uses its own random schema and requires pgvector already installed by Flyway. */
@EnabledIfEnvironmentVariable(named="RUN_SEMANTIC_PG_TEST",matches="true")
class SemanticRetrievalPostgresTest {
    @TempDir Path workspace;
    static final class FakeEmbeddings extends OllamaEmbeddings {
        boolean fail;String digest="a".repeat(64);
        FakeEmbeddings(){super("http://127.0.0.1:11434","embeddinggemma:latest");}
        @Override public String digest(){return digest;}
        @Override public List<String> embed(List<String> input,Duration timeout){
            if(fail)throw new IllegalStateException("Controlled model outage");
            return input.stream().map(ignored->"[1,"+"0,".repeat(766)+"0]").toList();
        }
    }
    @Test void storesVectorsScopesSnapshotsAndKeepsPublishedIndexAfterFailedRebuild() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local database only");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="semantic_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)){
            try(var statement=admin.createStatement();var row=statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'")){
                assertTrue(row.next());assertEquals(1,row.getInt(1),"Start the updated backend once so Flyway installs pgvector first");
            }
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try{
                var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
                var jdbc=new JdbcTemplate(dataSource);var transactions=new DataSourceTransactionManager(dataSource);
                try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                    connection.setAutoCommit(false);
                    for(String file:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql","V6__add_java_relationship_graphs.sql","V7__add_semantic_retrieval.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",file)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),otherSnapshot=UUID.randomUUID(),other=UUID.randomUUID();
                Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);
                Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n int charge() { return 42; }\n}\n");
                Path otherBase=workspace.resolve(other.toString()).resolve("base");Files.createDirectories(otherBase);
                Files.writeString(otherBase.resolve("Checkout.java"),Files.readString(base.resolve("Checkout.java")));
                String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Semantic test");
                for(var pair:List.of(Map.entry(snapshot,base),Map.entry(otherSnapshot,otherBase)))jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash) VALUES (?,?,?,?,?,?)",pair.getKey(),project,workspace.toString(),"b".repeat(40),pair.getValue().toString(),hash);
                for(var pair:List.of(Map.entry(owner,snapshot),Map.entry(other,otherSnapshot)))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",pair.getKey(),project,pair.getValue(),"Semantic test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(transactions),workspace.toString(),workspace.toString());
                var search=new SnapshotSearchService(jdbc,imports);var graphs=new JavaGraphService(jdbc,imports);
                var model=new FakeEmbeddings();var retrieval=new SemanticRetrievalService(jdbc,imports,search,graphs,model,transactions);
                new JavaIndexService(jdbc,imports).create(owner);graphs.create(owner);
                retrieval.build(owner);
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM semantic_indexes WHERE state='COMPLETE'",Integer.class));
                assertTrue(jdbc.queryForObject("SELECT min(vector_dims(embedding)) FROM semantic_chunks",Integer.class)==768);
                var matches=retrieval.retrieve(owner,"charge",10,true,true);
                assertTrue(matches.semanticUsed());assertTrue(matches.contexts().stream().anyMatch(hit->hit.channels().containsKey("SEMANTIC")&&hit.channels().containsKey("SYMBOL")));
                // An unrelated snapshot has no semantic index, even with the same source hash.
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM semantic_indexes WHERE snapshot_id=?",Integer.class,otherSnapshot));
                var unrelated=retrieval.retrieve(other,"charge",10,true,false);assertFalse(unrelated.semanticUsed());
                assertTrue(unrelated.notices().stream().anyMatch(notice->notice.contains("Build embeddings")));
                model.fail=true;assertThrows(org.springframework.web.server.ResponseStatusException.class,()->retrieval.build(owner));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM semantic_indexes WHERE state='FAILED'",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM semantic_indexes WHERE state='COMPLETE'",Integer.class));
                var fallback=retrieval.retrieve(owner,"charge",10,true,false);assertFalse(fallback.semanticUsed());assertFalse(fallback.contexts().isEmpty());
                model.fail=false;model.digest="b".repeat(64);
                assertTrue(retrieval.retrieve(owner,"charge",10,true,false).notices().stream().anyMatch(notice->notice.contains("different model digest")));
                assertEquals(hash,GitSnapshot.contentHash(base));
            }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
}
