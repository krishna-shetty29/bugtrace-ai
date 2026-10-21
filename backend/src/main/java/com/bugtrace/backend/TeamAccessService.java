package com.bugtrace.backend;

import java.time.*;
import java.util.*;
import java.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamAccessService {
    public record Issue(String name,String role,UUID snapshotId,String workerId,int days) {}
    public record Session(TeamAccessContext.Actor actor,String csrf) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final String bootstrap;
    private static final SecureRandom RANDOM=new SecureRandom();
    public TeamAccessService(JdbcTemplate jdbc,PlatformTransactionManager manager,@Value("${BUGTRACE_BOOTSTRAP_SECRET:}") String bootstrap) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.bootstrap=bootstrap;
    }
    static String secret(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static boolean keyFormat(String key){return key!=null&&key.matches("[A-Za-z0-9_-]{43}");}
    static String hash(String key){return ContextDocuments.hash(key);}
    boolean initialized(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT initialized FROM team_access_state WHERE id=1",Boolean.class));}
    void throttle(String client) {
        var rows=jdbc.queryForList("INSERT INTO team_login_limits(client_hash,window_started,attempts) VALUES (?,clock_timestamp(),1) ON CONFLICT(client_hash) DO UPDATE SET attempts=CASE WHEN team_login_limits.window_started<clock_timestamp()-INTERVAL '1 minute' THEN 1 ELSE team_login_limits.attempts+1 END,window_started=CASE WHEN team_login_limits.window_started<clock_timestamp()-INTERVAL '1 minute' THEN clock_timestamp() ELSE team_login_limits.window_started END RETURNING attempts",hash(client));
        if(((Number)rows.getFirst().get("attempts")).intValue()>10)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many authentication attempts; wait one minute");
        jdbc.update("DELETE FROM team_login_limits WHERE window_started<clock_timestamp()-INTERVAL '1 day'");
    }
    public List<Map<String,Object>> bootstrap(String supplied) {
        if(bootstrap==null||bootstrap.length()<32||supplied==null||supplied.length()>200||!MessageDigest.isEqual(hash(bootstrap).getBytes(java.nio.charset.StandardCharsets.US_ASCII),hash(supplied).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw denied("Bootstrap unavailable or secret invalid");
        return tx.execute(status->{
            var state=jdbc.queryForMap("SELECT initialized FROM team_access_state WHERE id=1 FOR UPDATE");
            if(Boolean.TRUE.equals(state.get("initialized")))throw denied("Bootstrap already completed");
            if(jdbc.queryForObject("SELECT count(*) FROM projects",Integer.class)==0)jdbc.update("INSERT INTO projects(id,name,description) VALUES (?,'My project','Project created during access bootstrap')",UUID.randomUUID());
            var keys=new ArrayList<Map<String,Object>>();
            for(var project:jdbc.queryForList("SELECT id,name FROM projects ORDER BY created_at,id")) {
                UUID id=(UUID)project.get("id");var issued=insert(id,new Issue("Initial project owner","OWNER",null,null,30));
                var row=new LinkedHashMap<>(issued);row.put("projectName",project.get("name"));keys.add(row);
                TeamAccessContext.audit(jdbc,new TeamAccessContext.Actor((UUID)issued.get("credentialId"),id,"Initial project owner","HUMAN","OWNER",null,null),UUID.randomUUID(),"BOOTSTRAP_OWNER",id.toString(),"RECORDED",null);
            }
            jdbc.update("UPDATE team_access_state SET initialized=TRUE WHERE id=1");return keys;
        });
    }
    TeamAccessContext.Actor authenticate(String key) {
        if(!keyFormat(key))throw unauthenticated();
        var rows=jdbc.queryForList("SELECT * FROM team_credentials WHERE token_hash=? AND revoked=FALSE AND expires_at>clock_timestamp()",hash(key));
        if(rows.size()!=1)throw unauthenticated();return actor(rows.getFirst());
    }
    Session session(String token) {
        if(!keyFormat(token))throw unauthenticated();
        var rows=jdbc.queryForList("SELECT c.*,s.csrf_token FROM team_sessions s JOIN team_credentials c ON c.id=s.credential_id WHERE s.token_hash=? AND s.expires_at>clock_timestamp() AND c.expires_at>clock_timestamp() AND c.revoked=FALSE AND c.kind='HUMAN'",hash(token));
        if(rows.size()!=1)throw unauthenticated();var row=rows.getFirst();return new Session(actor(row),row.get("csrf_token").toString());
    }
    Map<String,Object> login(String key) {
        var actor=authenticate(key);if(!"HUMAN".equals(actor.kind()))throw unauthenticated();
        String token=secret(),csrf=secret();
        jdbc.update("DELETE FROM team_sessions WHERE expires_at<=clock_timestamp()");
        jdbc.update("INSERT INTO team_sessions(token_hash,credential_id,csrf_token,expires_at) VALUES (?,?,?,clock_timestamp()+INTERVAL '1 hour')",hash(token),actor.id(),csrf);
        TeamAccessContext.audit(jdbc,actor,UUID.randomUUID(),"LOGIN",actor.id().toString(),"RECORDED",null);
        return Map.of("token",token,"csrf",csrf,"actor",actor);
    }
    void logout(String token){if(keyFormat(token)){jdbc.update("DELETE FROM team_sessions WHERE token_hash=?",hash(token));if(TeamAccessContext.actor()!=null)TeamAccessContext.record(jdbc,"LOGOUT",TeamAccessContext.actor().id());}}
    public List<Map<String,Object>> credentials() {
        var a=owner();return jdbc.queryForList("SELECT id,display_name,kind,role,snapshot_id,worker_id,revoked,expires_at,created_at FROM team_credentials WHERE project_id=? ORDER BY created_at,id",a.projectId());
    }
    public Map<String,Object> issue(Issue input) {
        var a=owner();return tx.execute(status->{lockProject(a.projectId());owner();return insert(a.projectId(),input);});
    }
    private Map<String,Object> insert(UUID project,Issue input) {
        if(input==null||input.name()==null||input.name().isBlank()||input.name().length()>80||input.days()<1||input.days()>90)throw denied("Supply a name and expiry between 1 and 90 days");
        boolean worker=input.snapshotId()!=null;
        if(worker) {
            if(input.role()!=null||input.workerId()==null||!input.workerId().matches("[A-Za-z0-9_-]{1,64}")||jdbc.queryForObject("SELECT count(*) FROM repository_snapshots WHERE id=? AND project_id=?",Integer.class,input.snapshotId(),project)!=1)throw denied("Worker snapshot must belong to this project; supply worker ID and no human role");
        }else if(!Set.of("OWNER","INVESTIGATOR","REVIEWER","VIEWER").contains(Objects.toString(input.role(),""))||input.workerId()!=null)throw denied("Select an explicit human role");
        UUID id=UUID.randomUUID();String key=secret();var expires=OffsetDateTime.now().plusDays(input.days());
        jdbc.update("INSERT INTO team_credentials(id,project_id,display_name,kind,role,token_hash,snapshot_id,worker_id,expires_at) VALUES (?,?,?,?,?,?,?,?,?)",id,project,input.name().strip(),worker?"WORKER":"HUMAN",input.role(),hash(key),input.snapshotId(),input.workerId(),expires);
        TeamAccessContext.record(jdbc,"CREDENTIAL_ISSUED",id);
        return Map.of("credentialId",id,"projectId",project,"accessKey",key,"expiresAt",expires,"notice","Shown once; keep it private. The database stores only its hash.");
    }
    public void revoke(UUID target) {
        var a=owner();tx.execute(status->{lockProject(a.projectId());owner();
            var rows=jdbc.queryForList("SELECT * FROM team_credentials WHERE id=? AND project_id=? FOR UPDATE",target,a.projectId());
            if(rows.size()!=1)throw denied("Credential does not belong to this project");var row=rows.getFirst();
            if("OWNER".equals(row.get("role"))&&!Boolean.TRUE.equals(row.get("revoked"))&&jdbc.queryForObject("SELECT count(*) FROM team_credentials WHERE project_id=? AND kind='HUMAN' AND role='OWNER' AND revoked=FALSE AND expires_at>clock_timestamp() AND id<>?",Integer.class,a.projectId(),target)==0)throw denied("Create another active owner credential before revoking the last owner");
            jdbc.update("UPDATE team_credentials SET revoked=TRUE WHERE id=?",target);jdbc.update("DELETE FROM team_sessions WHERE credential_id=?",target);TeamAccessContext.record(jdbc,"CREDENTIAL_REVOKED",target);return null;
        });
    }
    public Map<String,Object> createProject(String name) {
        owner();if(name==null||name.isBlank()||name.length()>200)throw denied("Supply a project name");
        return tx.execute(status->{UUID id=UUID.randomUUID();jdbc.update("INSERT INTO projects(id,name) VALUES (?,?)",id,name.strip());return insert(id,new Issue("Project owner","OWNER",null,null,30));});
    }
    public List<Map<String,Object>> audit() {var a=owner();return jdbc.queryForList("SELECT id,request_id,actor_id,actor_kind,actor_role,action,target,outcome,http_status,created_at FROM team_audit_events WHERE project_id=? ORDER BY created_at DESC,id LIMIT 500",a.projectId());}
    void event(TeamAccessContext.Actor a,UUID request,String action,String target,String outcome,Integer status){TeamAccessContext.audit(jdbc,a,request,action,target,outcome,status);}
    JdbcTemplate jdbc(){return jdbc;}
    private void lockProject(UUID id){jdbc.queryForMap("SELECT id FROM projects WHERE id=? FOR UPDATE",id);}
    TeamAccessContext.Actor owner() {
        var a=TeamAccessContext.actor();if(a==null||!"HUMAN".equals(a.kind())||!"OWNER".equals(a.role())||jdbc.queryForObject("SELECT count(*) FROM team_credentials WHERE id=? AND project_id=? AND role='OWNER' AND revoked=FALSE AND expires_at>clock_timestamp()",Integer.class,a.id(),a.projectId())!=1)throw denied("Project owner permission required");return a;
    }
    static TeamAccessContext.Actor actor(Map<String,Object> row){return new TeamAccessContext.Actor((UUID)row.get("id"),(UUID)row.get("project_id"),row.get("display_name").toString(),row.get("kind").toString(),(String)row.get("role"),(UUID)row.get("snapshot_id"),(String)row.get("worker_id"));}
    static ResponseStatusException denied(String message){return new ResponseStatusException(HttpStatus.FORBIDDEN,message);}
    static ResponseStatusException unauthenticated(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required or credential expired/revoked");}
}
