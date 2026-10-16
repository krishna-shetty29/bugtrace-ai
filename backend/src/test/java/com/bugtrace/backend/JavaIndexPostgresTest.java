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

@EnabledIfEnvironmentVariable(named="RUN_INDEX_PG_TEST",matches="true")
class JavaIndexPostgresTest {
    @TempDir Path workspace;
    @Test void persistsPartialIndexAndSharesItAcrossReports() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if (!url.startsWith("jdbc:postgresql://localhost:") && !url.startsWith("jdbc:postgresql://127.0.0.1:"))
            throw new IllegalArgumentException("This integration test only supports local PostgreSQL");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="java_index_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin=DriverManager.getConnection(url,user,password)) {
            try (Statement statement=admin.createStatement()) { statement.execute("CREATE SCHEMA "+schema); }
            try {
                var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
                var jdbc=new JdbcTemplate(source);
                try (Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    for (String name:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V5__add_java_symbol_indexes.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",name)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),report=UUID.randomUUID();
                Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);
                Files.writeString(base.resolve("Good.java"),"package demo; class Good { int count; void run() {} }");
                Files.writeString(base.resolve("Broken.java"),"class Broken { void run( }");
                String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Java index test");
                jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash) VALUES (?,?,?,?,?,?)",
                    snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash);
                for (UUID id:List.of(owner,report))
                    jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",
                        id,project,snapshot,"Index test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),workspace.toString(),workspace.toString());
                var indexer=new JavaIndexService(jdbc,imports);
                var result=indexer.create(report);
                assertEquals(snapshot,result.get("snapshotId"));
                Map<?,?> index=(Map<?,?>)result.get("index");
                assertEquals("PARTIAL",index.get("status"));assertEquals(1,((Number)index.get("failedFiles")).intValue());
                Map<?,?> reloaded=(Map<?,?>)new JavaIndexService(new JdbcTemplate(source),imports).get(owner).get("index");
                assertEquals(index,reloaded);
                indexer.create(owner);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM java_symbol_indexes",Integer.class));
                assertEquals(hash,GitSnapshot.contentHash(base));
            } finally {
                try (Statement statement=admin.createStatement()) { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
            }
        }
    }
}
