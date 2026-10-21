package com.bugtrace.backend;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Presence is informational. It never renews a job lease or authorizes execution. */
@Service
public class WorkerPresenceService {
    public record Contact(String workerId,UUID snapshotId,String contentHash,String readiness,UUID currentExecutionId) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public WorkerPresenceService(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;tx=new TransactionTemplate(manager);}
    public Map<String,Object> publish(Contact input) {
        var actor=TeamAccessContext.actor();
        if(actor==null)throw TeamAccessService.unauthenticated();
        if(!"WORKER".equals(actor.kind())||input==null||!Objects.equals(actor.workerId(),input.workerId())
            ||!Objects.equals(actor.snapshotId(),input.snapshotId())||input.contentHash()==null||!input.contentHash().matches("[0-9a-f]{64}")
            ||!Set.of("BASE_VERIFIED","NOT_READY").contains(Objects.toString(input.readiness(),"")))throw TeamAccessService.denied("Presence differs from worker registration");
        return tx.execute(status->{
            var credentials=jdbc.queryForList("SELECT id FROM team_credentials WHERE id=? AND project_id=? AND kind='WORKER' AND snapshot_id=? AND worker_id=? AND revoked=FALSE AND expires_at>clock_timestamp() FOR UPDATE",actor.id(),actor.projectId(),input.snapshotId(),input.workerId());
            if(credentials.size()!=1)throw TeamAccessService.unauthenticated();
            if(jdbc.queryForObject("SELECT count(*) FROM repository_snapshots WHERE id=? AND project_id=? AND content_hash=?",Integer.class,input.snapshotId(),actor.projectId(),input.contentHash())!=1)throw TeamAccessService.denied("Presence snapshot/hash mismatch");
            if(input.currentExecutionId()!=null&&jdbc.queryForObject("SELECT count(*) FROM execution_jobs WHERE attempt_execution_id=? AND snapshot_id=? AND worker_id=? AND queue_state='RUNNING' AND lease_until>clock_timestamp()",Integer.class,input.currentExecutionId(),input.snapshotId(),input.workerId())!=1)throw TeamAccessService.denied("Presence execution is outside the active worker lease");
            return jdbc.queryForMap("""
                INSERT INTO worker_presence(credential_id,project_id,worker_id,snapshot_id,content_hash,last_contact_at,readiness,current_execution_id)
                VALUES (?,?,?,?,?,clock_timestamp(),?,?)
                ON CONFLICT(credential_id) DO UPDATE SET last_contact_at=clock_timestamp(),readiness=EXCLUDED.readiness,current_execution_id=EXCLUDED.current_execution_id,content_hash=EXCLUDED.content_hash
                RETURNING last_contact_at AS "acceptedAt"
                """,actor.id(),actor.projectId(),input.workerId(),input.snapshotId(),input.contentHash(),input.readiness(),input.currentExecutionId());
        });
    }
    public List<Map<String,Object>> list() {
        var actor=TeamAccessContext.actor();
        if(actor==null)throw TeamAccessService.unauthenticated();
        if(!TeamPermissionPolicy.permitted(actor,"READ"))throw TeamAccessService.denied("Human project read access required");
        return jdbc.queryForList("""
            SELECT c.id AS "credentialId",c.project_id AS "projectId",c.worker_id AS "workerId",c.snapshot_id AS "snapshotId",
              s.content_hash AS "registeredContentHash",p.content_hash AS "reportedContentHash",p.last_contact_at AS "lastContactAt",
              p.readiness,p.current_execution_id AS "currentExecutionId",c.revoked AS "credentialRevoked",c.expires_at AS "credentialExpiresAt",
              clock_timestamp() AS "serverTime",
              CASE WHEN c.revoked THEN 'REVOKED' WHEN c.expires_at<=clock_timestamp() THEN 'EXPIRED'
                   WHEN p.last_contact_at IS NULL THEN 'NEVER_SEEN'
                   WHEN p.last_contact_at<=clock_timestamp()-INTERVAL '60 seconds' THEN 'OFFLINE'
                   WHEN p.content_hash IS DISTINCT FROM s.content_hash OR p.readiness='NOT_READY' THEN 'NOT_READY'
                   WHEN p.current_execution_id IS NOT NULL THEN 'BUSY' ELSE 'IDLE' END AS availability
            FROM team_credentials c JOIN repository_snapshots s ON s.id=c.snapshot_id AND s.project_id=c.project_id
            LEFT JOIN worker_presence p ON p.credential_id=c.id AND p.project_id=c.project_id AND p.worker_id=c.worker_id AND p.snapshot_id=c.snapshot_id
            WHERE c.kind='WORKER' AND c.project_id=? ORDER BY c.worker_id,c.id
            """,actor.projectId());
    }
}
