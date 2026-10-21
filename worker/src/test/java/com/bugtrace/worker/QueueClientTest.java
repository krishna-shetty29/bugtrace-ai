package com.bugtrace.worker;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import com.bugtrace.worker.WorkerController.*;

class QueueClientTest {
    @TempDir Path temp;
    private final JsonMapper mapper=JsonMapper.builder().build();

    private static class Engine extends WorkerService {
        final UUID snapshot,attempt;
        final Path evidence,stdout,stderr;
        boolean finished;
        int cancellations;
        Engine(Path temp,UUID snapshot,UUID attempt,boolean finished) throws Exception {
            super(snapshot.toString(),"a".repeat(64),temp.toString(),temp.toString(),temp.toString(),"maven@sha256:"+"a".repeat(64));
            this.snapshot=snapshot;this.attempt=attempt;this.finished=finished;
            Path folder=Files.createDirectory(temp.resolve("evidence"));
            stdout=folder.resolve("stdout.log");stderr=folder.resolve("stderr.log");
            Files.writeString(stdout,"test output");Files.writeString(stderr,"test stderr");
            evidence=folder.resolve("execution.json");
            ExecutionEvidence.write(folder,Map.of("executionId",attempt.toString(),"snapshotId",snapshot.toString(),
                "snapshotContentHash","a".repeat(64),"operation","RUN_SELECTED_TEST","selectedTest","CheckoutIdempotencyTest",
                "state","SUCCEEDED","classification","PASSED","exitCode",0));
        }
        @Override public Result submit(JobRequest job) { if (job.operation()==Operation.CANCEL_EXECUTION) cancellations++; return result(job.executionId()); }
        @Override public Result result(UUID id) {
            return new Result(attempt,snapshot,Operation.RUN_SELECTED_TEST,finished ? "SUCCEEDED" : "RUNNING",
                finished ? 0 : null,null,tempPath(),stdout.toString(),Instant.now(),finished ? Instant.now() : null,
                finished ? "PASSED" : null,evidence.toString(),stdout.toString(),stderr.toString());
        }
        private String tempPath() { return evidence.getParent().toString(); }
    }

    @Test void claimsHeartbeatsAndUploadsStructuredEvidence() throws Exception {
        run(200);
    }
    @Test void cancelsLocalAttemptWhenHeartbeatIsFencedOut() throws Exception {
        run(409);
    }
    @Test void cancelsLocalAttemptWhenWorkerKeyIsRevoked()throws Exception {run(401);}
    @Test void cancelsLocalAttemptWhenWorkerPermissionIsRevoked()throws Exception {run(403);}
    private void run(int heartbeatStatus) throws Exception {
        boolean loseLease=heartbeatStatus!=200;
        UUID job=UUID.randomUUID(),snapshot=UUID.randomUUID(),attempt=UUID.randomUUID(),token=UUID.randomUUID();
        var engine=new Engine(temp,snapshot,attempt,!loseLease);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger beats=new AtomicInteger(),completions=new AtomicInteger();
        AtomicReference<Map> uploaded=new AtomicReference<>();
        server.createContext("/",exchange->{
            assertEquals("Bearer "+"k".repeat(43),exchange.getRequestHeaders().getFirst("Authorization"));
            String path=exchange.getRequestURI().getPath();
            int status=200;String body="{}";
            if (path.equals("/api/queue/claim")) {
                body=mapper.writeValueAsString(Map.of("jobId",job.toString(),"snapshotId",snapshot.toString(),
                    "executionId",attempt.toString(),"leaseToken",token.toString(),"operation","RUN_SELECTED_TEST",
                    "testSelector","CheckoutIdempotencyTest","timeoutSeconds",120,"attempt",1));
            } else if (path.endsWith("/heartbeat")) { beats.incrementAndGet(); if (loseLease) status=heartbeatStatus; }
            else if (path.endsWith("/complete")) {
                completions.incrementAndGet();
                uploaded.set(mapper.readValue(exchange.getRequestBody().readAllBytes(),Map.class));
            }
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try (var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var client=new QueueClient(engine,"http://127.0.0.1:"+server.getAddress().getPort(),"test-worker",snapshot.toString(),"a".repeat(64),"k".repeat(43));
            client.poll();
            assertEquals(1,beats.get());
            if (loseLease) { assertEquals(1,engine.cancellations);assertEquals(0,completions.get()); }
            else {
                assertEquals(1,completions.get());assertEquals("test output",uploaded.get().get("stdout"));
                assertEquals("test stderr",uploaded.get().get("stderr"));
                assertEquals(token.toString(),uploaded.get().get("leaseToken"));
                assertEquals("PASSED",((Map<?,?>)uploaded.get().get("metadata")).get("classification"));
            }
        } finally { server.stop(0);engine.shutdown(); }
    }
}
