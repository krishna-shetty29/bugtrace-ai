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
import static com.bugtrace.backend.QueueContracts.*;

@EnabledIfEnvironmentVariable(named="RUN_WORKFLOW_PG_TEST",matches="true")
class ManualWorkflowPostgresTest {
    @TempDir Path workspace;
    @Test void reusedFixtureCreatesDistinctReportAndIsClaimedByTheRegisteredWorker() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        if (!url.startsWith("jdbc:postgresql://localhost:") && !url.startsWith("jdbc:postgresql://127.0.0.1:"))
            throw new IllegalArgumentException("Only local PostgreSQL is supported by this test");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="workflow_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin=DriverManager.getConnection(url,user,password)) {
            try (Statement statement=admin.createStatement()) { statement.execute("CREATE SCHEMA "+schema); }
            try {
                var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
                var jdbc=new JdbcTemplate(source);
                try (Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    for (String name:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",name)));
                    connection.commit();
                }
                var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),owner=UUID.randomUUID();
                Path repository=workspace.resolve("repository"),base=workspace.resolve(owner.toString()).resolve("base");
                Files.createDirectories(base); Files.writeString(base.resolve("pom.xml"),"test source");
                String hash=GitSnapshot.contentHash(base),commit="f1bd5e38b7c98e70ab19be828bc17852ffd81839";
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Workflow test");
                jdbc.update("""
                    INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash,build_tool,module_path)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,snapshot,project,repository.toString(),commit,base.toString(),hash,"MAVEN","fixtures/checkout-timeout");
                jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",
                    owner,project,snapshot,"Importer","bug","one","two");
                var reports=new InvestigationService(jdbc);
                var imports=new SnapshotImportService(jdbc,tx,repository.toString(),workspace.toString());
                var workflow=new ManualWorkflowService(jdbc,imports,reports,snapshot.toString(),commit,repository.toString());
                var report=tx.execute(status->workflow.create(snapshot,"Browser report","retry","one charge","two charges"));
                assertNotNull(report); assertNotEquals(owner,report.id()); assertEquals(snapshot,report.snapshotId());
                assertEquals(true,imports.verifySnapshot(report.id()).get("verified"));
                var queue=new DurableQueueService(jdbc);
                var job=tx.execute(status->queue.enqueue(report.id(),new CreateJob(Operation.RUN_SELECTED_TEST,"CheckoutIdempotencyTest",300)));
                var claimed=tx.execute(status->queue.claim(new Claim("laptop-worker-01",snapshot)));
                assertNotNull(claimed); assertEquals(job.get("executionId"),claimed.get("jobId"));
                assertEquals(snapshot,claimed.get("snapshotId"));
                Map<String,Object> metadata=new LinkedHashMap<>();
                metadata.put("executionId",claimed.get("executionId").toString());metadata.put("snapshotId",snapshot.toString());
                metadata.put("snapshotContentHash",hash);metadata.put("operation","RUN_SELECTED_TEST");
                metadata.put("selectedTest","CheckoutIdempotencyTest");metadata.put("state","FAILED");
                metadata.put("classification","ASSERTION_FAILED");metadata.put("exitCode",1);
                UUID jobId=(UUID)job.get("executionId");
                tx.execute(status->queue.complete(jobId,new Complete((UUID)claimed.get("leaseToken"),"laptop-worker-01",metadata,"expected: <1> but was: <2>","")));
                assertEquals("COMPLETED",queue.view(jobId).get("queueState"));
                assertEquals(report.id(),queue.view(jobId).get("investigationId"));
                assertEquals(hash,GitSnapshot.contentHash(base));
            } finally {
                try (Statement statement=admin.createStatement()) { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
            }
        }
    }
}
