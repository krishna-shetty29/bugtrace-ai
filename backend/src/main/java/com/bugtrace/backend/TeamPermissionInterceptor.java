package com.bugtrace.backend;
import java.util.*;
import java.util.regex.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@Component
public class TeamPermissionInterceptor implements HandlerInterceptor {
    private final TeamAccessService access;private final Map<String,String> endpoints;private final JsonMapper json=JsonMapper.builder().build();
    public TeamPermissionInterceptor(TeamAccessService access)throws Exception {
        this.access=access;
        try(var in=getClass().getResourceAsStream("/team-endpoints.json")){if(in==null)throw new IllegalStateException("Missing fail-closed endpoint policy");endpoints=Map.copyOf(json.readValue(in,Map.class));}
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception {
        if(!(handler instanceof HandlerMethod method))throw TeamAccessService.denied("No approved API handler");
        String key=method.getBeanType().getSimpleName()+"."+method.getMethod().getName();String path=request.getRequestURI();
        if(Set.of("HealthController.health","HelloController.hello").contains(key))return true;
        if(Set.of("TeamAccessController.status","TeamAccessController.login","TeamAccessController.bootstrap").contains(key))return true;
        var actor=TeamAccessContext.actor();if(actor==null)throw TeamAccessService.unauthenticated();
        String action=endpoints.get(key);
        boolean ok=false;
        try {
            if("WORKER".equals(actor.kind())) {worker(request,actor,key);ok=true;}
            else {
                if(!TeamPermissionPolicy.permitted(actor,action))throw TeamAccessService.denied("Role does not permit this operation");
                if(key.equals("InvestigationController.createProject"))throw TeamAccessService.denied("Create projects through Access management to receive a project-scoped owner key");
                UUID project=project(request,key);
                if(project!=null&&!project.equals(actor.projectId()))throw TeamAccessService.denied("Resource is outside this project");
                ok=true;
            }
        }finally {
            UUID trace=(UUID)request.getAttribute("team.trace");if(trace==null)trace=UUID.randomUUID();
            access.event(actor,trace,key,path,ok?"ATTEMPT":"DENIED",ok?null:403);
        }
        request.setAttribute("team.action",key);return true;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception failure) {
        Object action=request.getAttribute("team.action");if(action==null)return;
        try {access.event(TeamAccessContext.actor(),(UUID)request.getAttribute("team.trace"),action.toString(),request.getRequestURI(),failure==null?"HTTP_RESULT":"ERROR",failure==null?response.getStatus():500);}
        catch(Exception auditFailure){org.slf4j.LoggerFactory.getLogger(getClass()).error("Audit outcome unavailable for request {}. Its durable ATTEMPT must remain unresolved.",request.getAttribute("team.trace"));}
    }
    private JsonNode body(HttpServletRequest request){if(!(request instanceof TeamAccessFilter.Body wrapped))return json.createObjectNode();try{return wrapped.bytes.length==0?json.createObjectNode():json.readTree(wrapped.bytes);}catch(Exception invalid){throw TeamAccessService.denied("Invalid JSON request");}}
    private UUID id(String text){try{return UUID.fromString(text);}catch(Exception invalid){throw TeamAccessService.denied("Invalid resource identifier");}}
    private UUID one(String sql,UUID key){var rows=access.jdbc().queryForList(sql,key);if(rows.size()!=1)throw TeamAccessService.denied("Resource unavailable in this project");return (UUID)rows.getFirst().get("project_id");}
    private UUID project(HttpServletRequest request,String key) {
        String path=request.getRequestURI();Matcher inv=Pattern.compile("^/api/(?:benchmark-browser/)?investigations/([0-9a-fA-F-]{36})(?:/|$)").matcher(path);
        if(inv.find())return one("SELECT project_id FROM investigations WHERE id=?",id(inv.group(1)));
        Matcher execution=Pattern.compile("^/api/executions/([0-9a-fA-F-]{36})(?:/|$)").matcher(path);
        if(execution.find())return one("SELECT i.project_id FROM executions e JOIN investigations i ON i.id=e.investigation_id WHERE e.id=?",id(execution.group(1)));
        if(key.equals("InvestigationController.createInvestigation"))return id(body(request).path("projectId").asText());
        if(key.equals("ManualWorkflowController.create"))return one("SELECT project_id FROM repository_snapshots WHERE id=?",id(body(request).path("snapshotId").asText()));
        if(key.equals("BenchmarkSnapshotController.capture"))return one("SELECT project_id FROM investigations WHERE id=?",id(body(request).path("investigationId").asText()));
        if(key.equals("BenchmarkSnapshotController.verify")) {
            Matcher selected=Pattern.compile("/snapshots/([0-9a-fA-F-]{36})/verify$").matcher(path);if(!selected.find())throw TeamAccessService.denied("Invalid snapshot verification route");return one("SELECT project_id FROM investigations WHERE id=?",id(selected.group(1)));
        }
        return null; // Only explicitly catalogued non-resource methods can reach this branch.
    }
    private void worker(HttpServletRequest request,TeamAccessContext.Actor actor,String key) {
        var b=body(request);String path=request.getRequestURI();
        if(key.equals("DurableQueueController.claim")) {
            if(!actor.snapshotId().equals(id(b.path("snapshotId").asText()))||!actor.workerId().equals(b.path("workerId").asText()))throw TeamAccessService.denied("Worker claim is outside its registration");return;
        }
        if(Set.of("DurableQueueController.beat","DurableQueueController.complete").contains(key)) {
            var match=Pattern.compile("/queue/([0-9a-fA-F-]{36})/").matcher(path);if(!match.find())throw TeamAccessService.denied("Invalid queue route");
            var rows=access.jdbc().queryForList("SELECT j.snapshot_id,j.worker_id,i.project_id FROM execution_jobs j JOIN executions e ON e.id=j.id JOIN investigations i ON i.id=e.investigation_id WHERE j.id=?",id(match.group(1)));
            if(rows.size()!=1||!actor.projectId().equals(rows.getFirst().get("project_id"))||!actor.snapshotId().equals(rows.getFirst().get("snapshot_id"))||!actor.workerId().equals(rows.getFirst().get("worker_id"))||!actor.workerId().equals(b.path("workerId").asText()))throw TeamAccessService.denied("Worker cannot report this job");return;
        }
        if(!Set.of("BenchmarkRegistrationController.verify","BenchmarkSnapshotController.verify").contains(key))throw TeamAccessService.denied("Worker operation is not permitted");
        var registered=access.jdbc().queryForList("SELECT registration_id,investigation_id FROM benchmark_snapshot_imports WHERE snapshot_id=?",actor.snapshotId());
        if(registered.size()==1) {
            String registration=registered.getFirst().get("registration_id").toString(),investigation=registered.getFirst().get("investigation_id").toString();
            if(key.equals("BenchmarkRegistrationController.verify")&&path.equals("/api/benchmark-registrations/"+registration+"/verify"))return;
            if(key.equals("BenchmarkSnapshotController.verify")&&path.equals("/api/benchmark-registrations/"+registration+"/snapshots/"+investigation+"/verify"))return;
        }
        throw TeamAccessService.denied("Worker credentials cannot investigate, download artifacts, or approve patches");
    }
}
