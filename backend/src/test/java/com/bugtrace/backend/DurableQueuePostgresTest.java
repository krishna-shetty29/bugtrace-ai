package com.bugtrace.backend;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static com.bugtrace.backend.QueueContracts.*;

@EnabledIfEnvironmentVariable(named="RUN_QUEUE_PG_TEST",matches="true")
class DurableQueuePostgresTest {
    @Test void verifiesClaimsFencingRecoveryAndDurabilityInIsolatedSchema() throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"),"Load DB_URL first");
        if (!url.startsWith("jdbc:postgresql://localhost:") && !url.startsWith("jdbc:postgresql://127.0.0.1:"))
            throw new IllegalArgumentException("Integration test is restricted to local PostgreSQL");
        String user=Objects.requireNonNull(System.getenv("POSTGRES_USER"));
        String password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
        String schema="queue_test_"+UUID.randomUUID().toString().replace("-","");
        try (Connection admin=DriverManager.getConnection(url,user,password)) {
            try (Statement statement=admin.createStatement()) { statement.execute("CREATE SCHEMA "+schema); }
            try {
                String scopedUrl=url+(url.contains("?") ? "&" : "?")+"currentSchema="+schema;
                var source=new DriverManagerDataSource(scopedUrl,user,password);
                var jdbc=new JdbcTemplate(source);
                try (Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    for (String name:List.of("V2__create_initial_tables.sql","V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",name)));
                    connection.commit();
                }
                var service=new DurableQueueService(jdbc);
                var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
                UUID project=UUID.randomUUID(),snapshot=UUID.randomUUID(),investigation=UUID.randomUUID();
                jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",project,"Queue integration test");
                jdbc.update("INSERT INTO repository_snapshots(id,project_id,repository_url,commit_sha,source_location,content_hash) VALUES (?,?,?,?,?,?)",
                    snapshot,project,"local-test","a".repeat(40),"/test/base","a".repeat(64));
                jdbc.update("INSERT INTO investigations(id,project_id,snapshot_id,title,bug_description,expected_behavior,observed_behavior) VALUES (?,?,?,?,?,?,?)",
                    investigation,project,snapshot,"Queue test","test","one","two");
                var job=tx.execute(s->service.enqueue(investigation,new CreateJob(Operation.RUN_SELECTED_TEST,"CheckoutIdempotencyTest",120)));
                UUID id=(UUID)job.get("executionId");
                assertEquals("QUEUED",job.get("queueState"));

                Map<String,Object> first;
                String owner;
                try (var executor=Executors.newFixedThreadPool(2)) {
                    var a=executor.submit(()->tx.execute(s->service.claim(new Claim("test-worker-a",snapshot))));
                    var b=executor.submit(()->tx.execute(s->service.claim(new Claim("test-worker-b",snapshot))));
                    var ra=a.get(10,TimeUnit.SECONDS);var rb=b.get(10,TimeUnit.SECONDS);
                    assertTrue((ra==null)!=(rb==null),"Exactly one worker must claim the single job");
                    first=ra!=null ? ra : rb;owner=ra!=null ? "test-worker-a" : "test-worker-b";
                }
                UUID token=(UUID)first.get("leaseToken");
                tx.executeWithoutResult(s->service.heartbeat(id,new Heartbeat(token,owner,Map.of("state","RUNNING"))));
                assertEquals("RUNNING",((Map<?,?>)service.view(id).get("progress")).get("state"));
                jdbc.update("UPDATE execution_jobs SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",id);
                assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(s->service.heartbeat(id,new Heartbeat(token,owner,Map.of()))));
                tx.executeWithoutResult(s->service.recoverExpired());
                assertEquals("QUEUED",service.view(id).get("queueState"));
                var second=tx.execute(s->service.claim(new Claim(owner,snapshot)));
                assertNotEquals(first.get("executionId"),second.get("executionId"));
                assertNotEquals(first.get("leaseToken"),second.get("leaseToken"));
                var oldResult=completion(first,owner,snapshot);
                assertThrows(ResponseStatusException.class,()->tx.execute(s->service.complete(id,oldResult)));
                var completed=tx.execute(s->service.complete(id,completion(second,owner,snapshot)));
                assertEquals("COMPLETED",completed.get("queueState"));
                assertEquals("ASSERTION_FAILED",((Map<?,?>)completed.get("result")).get("classification"));
                assertEquals("expected: <1> but was: <2>",service.logs(id).get("stdout"));
                assertEquals("stderr evidence",service.logs(id).get("stderr"));
                assertEquals("COMPLETED",tx.execute(s->service.complete(id,completion(second,owner,snapshot))).get("queueState"));
                assertEquals("COMPLETED",new DurableQueueService(new JdbcTemplate(source)).view(id).get("queueState"));

                var retryJob=tx.execute(s->service.enqueue(investigation,new CreateJob(Operation.COMPILE,null,120)));
                UUID retryId=(UUID)retryJob.get("executionId");
                for (int i=0;i<3;i++) {
                    assertNotNull(tx.execute(s->service.claim(new Claim(owner,snapshot))));
                    jdbc.update("UPDATE execution_jobs SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",retryId);
                    tx.executeWithoutResult(s->service.recoverExpired());
                }
                assertEquals("FAILED",service.view(retryId).get("queueState"));
                assertEquals("ERROR",jdbc.queryForObject("SELECT execution_state FROM executions WHERE id=?",String.class,retryId));
            } finally {
                try (Statement statement=admin.createStatement()) { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
            }
        }
    }
    private Complete completion(Map<String,Object> claim,String worker,UUID snapshot) {
        Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("executionId",claim.get("executionId").toString());metadata.put("snapshotId",snapshot.toString());
        metadata.put("snapshotContentHash","a".repeat(64));metadata.put("operation","RUN_SELECTED_TEST");
        metadata.put("selectedTest","CheckoutIdempotencyTest");metadata.put("state","FAILED");
        metadata.put("classification","ASSERTION_FAILED");metadata.put("exitCode",1);
        return new Complete((UUID)claim.get("leaseToken"),worker,metadata,"expected: <1> but was: <2>","stderr evidence");
    }
}
