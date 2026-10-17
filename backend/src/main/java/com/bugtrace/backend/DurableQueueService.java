package com.bugtrace.backend;

import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static com.bugtrace.backend.QueueContracts.*;

@Service
public class DurableQueueService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public DurableQueueService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private String json(Object value) { return mapper.writeValueAsString(value); }
    private Map<String,Object> object(String value) { return value == null ? null : mapper.readValue(value, Map.class); }
    private static ResponseStatusException reject(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    @Transactional
    public Map<String,Object> enqueue(UUID investigationId, CreateJob job) {
        try { QueueContracts.validate(job); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        var rows = jdbc.queryForList("""
            SELECT s.id, s.source_location, s.content_hash FROM investigations i
            JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?
            """, investigationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Investigation not found");
        var snapshot = rows.getFirst();
        if (snapshot.get("source_location") == null || snapshot.get("content_hash") == null)
            throw reject("Import the snapshot before requesting execution");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO executions(id,investigation_id,command) VALUES (?,?,?)", id, investigationId, job.operation().name());
        jdbc.update("""
            INSERT INTO execution_jobs(id,snapshot_id,operation,test_selector,timeout_seconds)
            VALUES (?,?,?,?,?)
            """, id, snapshot.get("id"), job.operation().name(), job.testSelector(), job.timeoutSeconds());
        return view(id);
    }

    @Transactional
    public Map<String,Object> enqueueGenerated(UUID investigation,GeneratedReproduction.Spec spec,int timeout) {
        GeneratedReproduction.validateSource(spec.testSource());
        var job=enqueue(investigation,new QueueContracts.CreateJob(QueueContracts.Operation.RUN_SELECTED_TEST,GeneratedReproduction.SELECTOR,timeout));
        jdbc.update("UPDATE execution_jobs SET generated_test=CAST(? AS jsonb) WHERE id=?",json(GeneratedReproduction.overlay(spec)),job.get("executionId"));
        return job;
    }
    @Transactional
    public Map<String,Object> claim(Claim request) {
        // Serialize simultaneous requests from the same named laptop worker.
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, request.workerId());
        if (!Boolean.TRUE.equals(locked)) return null;
        Integer active = jdbc.queryForObject("SELECT count(*) FROM execution_jobs WHERE worker_id=? AND queue_state='RUNNING' AND lease_until>clock_timestamp()", Integer.class, request.workerId());
        if (active != null && active > 0) return null;
        expireQueuedDeadlines();
        UUID token = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        var claimed = jdbc.queryForList("""
            UPDATE execution_jobs SET queue_state='RUNNING',worker_id=?,lease_token=?,
                attempt_execution_id=?,attempts=attempts+1,
                lease_until=clock_timestamp()+INTERVAL '45 seconds',
                progress='{"state":"CLAIMED"}'::jsonb,technical_error=NULL,updated_at=clock_timestamp()
            WHERE id=(SELECT id FROM execution_jobs
                WHERE queue_state='QUEUED' AND snapshot_id=? AND attempts<max_attempts
                    AND (not_after IS NULL OR not_after>clock_timestamp()+INTERVAL '1 second')
                ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id,snapshot_id,operation,test_selector,timeout_seconds,attempts,generated_test,not_after
            """, request.workerId(), token, attempt, request.snapshotId());
        if (claimed.isEmpty()) return null;
        var row = claimed.getFirst();
        UUID id = (UUID) row.get("id");
        jdbc.update("""
            INSERT INTO execution_job_attempts(attempt_execution_id,job_id,attempt_number,worker_id,lease_token,attempt_state)
            VALUES (?,?,?,?,?,'RUNNING')
            """, attempt, id, row.get("attempts"), request.workerId(), token);
        jdbc.update("""
            UPDATE executions SET execution_state='RUNNING',started_at=clock_timestamp(),finished_at=NULL,
                exit_code=NULL,technical_error_code=NULL,technical_error_message=NULL WHERE id=?
            """, id);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("jobId", id); result.put("snapshotId", row.get("snapshot_id"));
        result.put("executionId", attempt); result.put("leaseToken", token);
        result.put("operation", row.get("operation")); result.put("testSelector", row.get("test_selector"));
        result.put("timeoutSeconds", row.get("timeout_seconds")); result.put("attempt", row.get("attempts"));
        result.put("leaseSeconds", 45);
        result.put("notAfter",row.get("not_after")==null?null:InvestigationLoopPolicy.instant(row.get("not_after")).toString());
        result.put("generatedTest",row.get("generated_test")==null?null:mapper.readTree(row.get("generated_test").toString()));
        return result;
    }

    @Transactional
    public void heartbeat(UUID id, Heartbeat beat) {
        String progress = json(beat.progress());
        if (progress.length() > 8192) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Progress exceeds 8 KiB");
        int changed = jdbc.update("""
            UPDATE execution_jobs SET lease_until=clock_timestamp()+INTERVAL '45 seconds',
                progress=CAST(? AS jsonb),updated_at=clock_timestamp()
            WHERE id=? AND worker_id=? AND lease_token=? AND queue_state='RUNNING'
                AND lease_until>clock_timestamp()
            """, progress, id, beat.workerId(), beat.leaseToken());
        if (changed != 1) throw reject("Lease expired, replaced, or completed");
    }

    @Transactional
    public Map<String,Object> complete(UUID id, Complete completion) {
        var rows = jdbc.queryForList("SELECT *,lease_until>clock_timestamp() AS live FROM execution_jobs WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        var row = rows.getFirst();
        if (!Objects.equals(row.get("worker_id"), completion.workerId()) || !Objects.equals(row.get("lease_token"), completion.leaseToken()))
            throw reject("Stale lease token");
        if (row.get("queue_state").equals("COMPLETED")) return view(id); // Idempotent retry of an accepted completion.
        if (!row.get("queue_state").equals("RUNNING") || !Boolean.TRUE.equals(row.get("live"))) throw reject("Lease is no longer live");
        String expectedHash = jdbc.queryForObject("SELECT content_hash FROM repository_snapshots WHERE id=?", String.class, row.get("snapshot_id"));
        try { QueueContracts.validateResult(completion.metadata(), (UUID)row.get("attempt_execution_id"),
                (UUID)row.get("snapshot_id"), (String)row.get("operation"), (String)row.get("test_selector"), expectedHash); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
        String metadata = json(completion.metadata());
        if (metadata.length() > 2097152) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Metadata exceeds 2 MiB");
        String classification = completion.metadata().get("classification").toString();
        jdbc.update("""
            UPDATE execution_jobs SET queue_state='COMPLETED',result=CAST(? AS jsonb),stdout=?,stderr=?,
                progress=CAST(? AS jsonb),completed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?
            """, metadata, completion.stdout(), completion.stderr(), json(Map.of("state","COMPLETED","classification",classification)),id);
        jdbc.update("""
            UPDATE execution_job_attempts SET attempt_state='COMPLETED',finished_at=clock_timestamp(),
                result=CAST(? AS jsonb),stdout=?,stderr=? WHERE attempt_execution_id=?
            """,metadata,completion.stdout(),completion.stderr(),row.get("attempt_execution_id"));
        String state = classification.equals("CANCELLED") ? "CANCELLED" :
            Set.of("PASSED","ASSERTION_FAILED","COMPILATION_FAILED").contains(classification) ? "FINISHED" : "ERROR";
        Object code = completion.metadata().get("exitCode");
        jdbc.update("""
            UPDATE executions SET execution_state=?,exit_code=?,result_summary=?,finished_at=clock_timestamp(),
                technical_error_code=?,technical_error_message=? WHERE id=?
            """,state,code,classification,state.equals("ERROR") ? classification : null,
            completion.metadata().get("technicalError"),id);
        return view(id);
    }

    @Transactional
    public void recoverExpired() {
        expireQueuedDeadlines();
        var expired = jdbc.queryForList("""
            SELECT id,attempt_execution_id,attempts,max_attempts FROM execution_jobs
            WHERE queue_state='RUNNING' AND lease_until<=clock_timestamp()
            ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
            """);
        for (var row : expired) {
            boolean exhausted = ((Number)row.get("attempts")).intValue() >= ((Number)row.get("max_attempts")).intValue();
            jdbc.update("""
                UPDATE execution_job_attempts SET attempt_state='EXPIRED',finished_at=clock_timestamp(),
                    technical_error='WORKER_LEASE_EXPIRED' WHERE attempt_execution_id=? AND attempt_state='RUNNING'
                """,row.get("attempt_execution_id"));
            jdbc.update("""
                UPDATE execution_jobs SET queue_state=?,worker_id=NULL,lease_token=NULL,lease_until=NULL,
                    attempt_execution_id=NULL,technical_error='WORKER_LEASE_EXPIRED',
                    progress='{"state":"LEASE_EXPIRED"}'::jsonb,updated_at=clock_timestamp(),
                    completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE id=?
                """,exhausted ? "FAILED" : "QUEUED",exhausted,row.get("id"));
            jdbc.update("""
                UPDATE executions SET execution_state=?,technical_error_code='WORKER_LEASE_EXPIRED',
                    technical_error_message='Worker lease expired before completion',
                    finished_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE id=?
                """,exhausted ? "ERROR" : "PENDING",exhausted,row.get("id"));
        }
    }

    private void expireQueuedDeadlines() {
        var expired=jdbc.queryForList("UPDATE execution_jobs SET queue_state='FAILED',technical_error='QUEUE_DEADLINE_EXPIRED',completed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE queue_state='QUEUED' AND not_after<=clock_timestamp()+INTERVAL '1 second' RETURNING id");
        for(var row:expired)jdbc.update("UPDATE executions SET execution_state='ERROR',technical_error_code='QUEUE_DEADLINE_EXPIRED',technical_error_message='Loop deadline expired before worker claim',finished_at=clock_timestamp() WHERE id=?",row.get("id"));
    }
    public Map<String,Object> view(UUID id) {
        var rows = jdbc.queryForList("""
            SELECT j.*,e.investigation_id FROM execution_jobs j JOIN executions e ON e.id=j.id WHERE j.id=?
            """,id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found");
        var r = rows.getFirst();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("executionId",id); result.put("investigationId",r.get("investigation_id"));
        result.put("snapshotId",r.get("snapshot_id")); result.put("operation",r.get("operation"));
        result.put("testSelector",r.get("test_selector")); result.put("queueState",r.get("queue_state"));
        result.put("attemptCount",r.get("attempts")); result.put("maxAttempts",r.get("max_attempts"));
        result.put("workerId",r.get("worker_id")); result.put("leaseUntil",r.get("lease_until"));
        result.put("attemptExecutionId",r.get("attempt_execution_id"));
        result.put("progress",object(r.get("progress").toString()));
        result.put("result",r.get("result")==null ? null : object(r.get("result").toString()));
        result.put("technicalError",r.get("technical_error")); result.put("createdAt",r.get("created_at"));
        result.put("completedAt",r.get("completed_at"));
        result.put("attempts",jdbc.queryForList("""
            SELECT attempt_execution_id,attempt_number,worker_id,attempt_state,started_at,finished_at,technical_error
            FROM execution_job_attempts WHERE job_id=? ORDER BY attempt_number
            """,id));
        return result;
    }
    public Map<String,Object> logs(UUID id) {
        var rows = jdbc.queryForList("SELECT stdout,stderr FROM execution_jobs WHERE id=?",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found");
        return rows.getFirst();
    }
}
