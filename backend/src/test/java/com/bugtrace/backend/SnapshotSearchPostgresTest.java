package com.bugtrace.backend;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.bugtrace.backend.SnapshotSearchEngine.*;

@EnabledIfEnvironmentVariable(named="RUN_SEARCH_PG_TEST",matches="true")
class SnapshotSearchPostgresTest {
    @TempDir Path workspace;
    @Test void searchesSavedSymbolsAndScopesErrorEvidenceToItsInvestigation() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))
            throw new IllegalArgumentException("This integration test supports only local PostgreSQL");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="snapshot_search_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(url,user,password)) {
            try(Statement statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try {
                var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
                var jdbc=new JdbcTemplate(source);
                try(Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    for(String name:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql","V5__add_java_symbol_indexes.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",name)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),otherReport=UUID.randomUUID(),execution=UUID.randomUUID();
                Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);
                Files.writeString(base.resolve("CheckoutService.java"),"// retry timeout idempotency\nclass CheckoutService { void charge() {} }");
                String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Search test");
                jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash) VALUES (?,?,?,?,?,?)",
                    snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash);
                for(UUID id:List.of(owner,otherReport))jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",
                    id,project,snapshot,"Search test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),workspace.toString(),workspace.toString());
                var search=new SnapshotSearchService(jdbc,imports);
                assertFalse(search.search(owner,"checkout",Kind.ALL,50).symbolsAvailable());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->search.search(owner,"checkout",Kind.CLASS,50));
                new JavaIndexService(jdbc,imports).create(owner);
                assertEquals("CLASS",search.search(owner,"checkout",Kind.CLASS,50).hits().getFirst().kind());
                assertEquals("METHOD",search.search(otherReport,"charge",Kind.METHOD,50).hits().getFirst().kind());
                jdbc.update("INSERT INTO executions(id,investigation_id,command,execution_state,started_at,finished_at) VALUES (?,?,?,'FINISHED',clock_timestamp(),clock_timestamp())",execution,owner,"mvn test");
                jdbc.update("INSERT INTO execution_jobs(id,snapshot_id,operation,test_selector,timeout_seconds,queue_state,stdout,stderr) VALUES (?,?,'RUN_SELECTED_TEST','CheckoutIdempotencyTest',120,'COMPLETED',?,?)",
                    execution,snapshot,"Header\nexpected: <1> but was: <2>\n","");
                var own=search.search(owner,"expected: <1> but was: <2>",Kind.ERROR,50);
                assertEquals(1,own.totalMatches());assertEquals(execution.toString(),own.hits().getFirst().executionId());assertEquals(2,own.hits().getFirst().line());
                assertEquals(0,search.search(otherReport,"expected: <1> but was: <2>",Kind.ERROR,50).totalMatches());
                assertEquals(hash,GitSnapshot.contentHash(base));
            } finally { try(Statement statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");} }
        }
    }
}
