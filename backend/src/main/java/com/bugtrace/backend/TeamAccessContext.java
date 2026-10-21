package com.bugtrace.backend;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Server-resolved identity; no user/role/project header is trusted. */
public final class TeamAccessContext {
    public record Actor(UUID id,UUID projectId,String name,String kind,String role,UUID snapshotId,String workerId) {}
    public record Request(Actor actor,UUID requestId) {}
    private static final ThreadLocal<Request> CURRENT=new ThreadLocal<>();
    private TeamAccessContext() {}
    public static Actor actor(){return CURRENT.get()==null?null:CURRENT.get().actor();}
    static void set(Actor actor,UUID request){CURRENT.set(new Request(actor,request));}
    static void clear(){CURRENT.remove();}
    public static UUID project(){return actor()==null?null:actor().projectId();}
    public static void requireProject(UUID project){if(actor()!=null&&!actor().projectId().equals(project))throw TeamAccessService.denied("Resource is outside this project");}
    public static void record(JdbcTemplate jdbc,String action,UUID target) {
        var request=CURRENT.get();if(request==null||request.actor()==null)return;
        audit(jdbc,request.actor(),request.requestId(),action,target.toString(),"RECORDED",null);
    }
    static void audit(JdbcTemplate jdbc,Actor actor,UUID request,String action,String target,String outcome,Integer status) {
        jdbc.update("INSERT INTO team_audit_events(id,request_id,project_id,actor_id,actor_kind,actor_role,action,target,outcome,http_status) VALUES (?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),request,actor==null?null:actor.projectId(),actor==null?null:actor.id(),actor==null?"ANONYMOUS":actor.kind(),actor==null?null:actor.role(),action,target,outcome,status);
    }
}
