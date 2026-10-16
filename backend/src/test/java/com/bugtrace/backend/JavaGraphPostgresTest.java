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

@EnabledIfEnvironmentVariable(named="RUN_GRAPH_PG_TEST",matches="true")
class JavaGraphPostgresTest {
    @TempDir Path workspace;
    @Test void persistsGraphReloadsItAndTraversesUncertainCallers() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if (!url.startsWith("jdbc:postgresql://localhost:") && !url.startsWith("jdbc:postgresql://127.0.0.1:"))
            throw new IllegalArgumentException("This integration test supports only local PostgreSQL");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="java_graph_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin=DriverManager.getConnection(url,user,password)) {
            try (Statement statement=admin.createStatement()) { statement.execute("CREATE SCHEMA "+schema); }
            try {
                var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
                var jdbc=new JdbcTemplate(source);
                try (Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    for (String name:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V6__add_java_relationship_graphs.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",name)));
                    connection.commit();
                }
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID(),report=UUID.randomUUID();
                Path base=workspace.resolve(owner.toString()).resolve("base");Files.createDirectories(base);
                Files.writeString(base.resolve("Checkout.java"),"class Service { void charge() {} } class Checkout { Service service; void run(){service.charge();} }");
                String hash=GitSnapshot.contentHash(base);
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Graph test");
                jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash) VALUES (?,?,?,?,?,?)",
                    snapshot,project,workspace.toString(),"a".repeat(40),base.toString(),hash);
                for (UUID id:List.of(owner,report)) jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",
                    id,project,snapshot,"Graph test","bug","one","two");
                var imports=new SnapshotImportService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),workspace.toString(),workspace.toString());
                var service=new JavaGraphService(jdbc,imports);
                var result=service.create(report);assertEquals(snapshot,result.get("snapshotId"));
                var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
                var graph=mapper.convertValue(result.get("graph"),JavaRelationshipAnalyzer.Graph.class);
                var charge=graph.nodes().stream().filter(node->node.kind().equals("METHOD")&&node.label().contains("Service.")).findFirst().orElseThrow();
                assertTrue(service.impact(report,charge.id(),3,true).reached().stream().anyMatch(node->node.uncertain()));
                assertEquals(result.get("graph"),new JavaGraphService(new JdbcTemplate(source),imports).get(owner).get("graph"));
                service.create(owner);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM java_relationship_graphs",Integer.class));
                assertEquals(hash,GitSnapshot.contentHash(base));
            } finally {
                try (Statement statement=admin.createStatement()) { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
            }
        }
    }
}
