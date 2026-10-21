package com.bugtrace.backend;
import java.util.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TeamPermissionInterceptorTest {
    private final TeamAccessService access=mock(TeamAccessService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final UUID project=UUID.randomUUID(),other=UUID.randomUUID(),resource=UUID.randomUUID();
    @AfterEach void clear(){TeamAccessContext.clear();}
    private void actor(String role){TeamAccessContext.set(new TeamAccessContext.Actor(UUID.randomUUID(),project,"Person","HUMAN",role,null,null),UUID.randomUUID());}
    private HandlerMethod handler(Class<?> type,String name){var factory=mock(org.springframework.beans.factory.BeanFactory.class);when(factory.getType("handler")).thenAnswer(call->type);return new HandlerMethod("handler",factory,Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow());}
    private MockHttpServletRequest request(String method,String path){var r=new MockHttpServletRequest(method,path);r.setRequestURI(path);r.setAttribute("team.trace",UUID.randomUUID());return r;}
    @Test void completeControllerCatalogHasNoUnclassifiedOperations()throws Exception {
        var scanner=new ClassPathScanningCandidateComponentProvider(false);scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));var actual=new TreeSet<String>();
        for(var bean:scanner.findCandidateComponents("com.bugtrace.backend"))for(Method m:Class.forName(bean.getBeanClassName()).getDeclaredMethods())if(AnnotatedElementUtils.hasAnnotation(m,RequestMapping.class))actual.add(m.getDeclaringClass().getSimpleName()+"."+m.getName());
        try(var in=getClass().getResourceAsStream("/team-endpoints.json")){var catalog=JsonMapper.builder().build().readValue(in,Map.class);assertEquals(actual,catalog.keySet());assertTrue(catalog.size()>100);}
    }
    @Test void viewerCannotStartExecutionOrForgeReviewer()throws Exception {
        actor("VIEWER");var r=request("POST","/api/investigations/"+resource+"/patch-reviews/"+UUID.randomUUID()+"/decision");r.addHeader("X-Role","OWNER");var guard=new TeamPermissionInterceptor(access);
        assertThrows(ResponseStatusException.class,()->guard.preHandle(r,new MockHttpServletResponse(),handler(PatchReviewController.class,"decision")));verifyNoInteractions(jdbc);
    }
    @Test void reviewerCannotExecuteAndInvestigatorCannotApprove()throws Exception {
        var guard=new TeamPermissionInterceptor(access);actor("REVIEWER");assertThrows(ResponseStatusException.class,()->guard.preHandle(request("POST","/api/investigations/"+resource+"/executions"),new MockHttpServletResponse(),handler(DurableQueueController.class,"create")));
        actor("INVESTIGATOR");assertThrows(ResponseStatusException.class,()->guard.preHandle(request("POST","/api/investigations/"+resource+"/patch-reviews/"+UUID.randomUUID()+"/decision"),new MockHttpServletResponse(),handler(PatchReviewController.class,"decision")));
    }
    @Test void ownProjectReadPermittedButForeignLogsAndDownloadsDenied()throws Exception {
        actor("VIEWER");when(access.jdbc()).thenReturn(jdbc);when(jdbc.queryForList(anyString(),eq(resource))).thenReturn(List.of(Map.of("project_id",project)));var guard=new TeamPermissionInterceptor(access);
        assertTrue(guard.preHandle(request("GET","/api/executions/"+resource+"/logs"),new MockHttpServletResponse(),handler(DurableQueueController.class,"logs")));
        when(jdbc.queryForList(anyString(),eq(resource))).thenReturn(List.of(Map.of("project_id",other)));
        assertThrows(ResponseStatusException.class,()->guard.preHandle(request("GET","/api/executions/"+resource+"/logs"),new MockHttpServletResponse(),handler(DurableQueueController.class,"logs")));
        assertThrows(ResponseStatusException.class,()->guard.preHandle(request("GET","/api/investigations/"+resource+"/patch-reviews/"+UUID.randomUUID()+"/export"),new MockHttpServletResponse(),handler(PatchReviewController.class,"export")));
    }
    @Test void workersCannotReadHumanArtifactsAndHumansCannotClaim()throws Exception {
        var guard=new TeamPermissionInterceptor(access);TeamAccessContext.set(new TeamAccessContext.Actor(UUID.randomUUID(),project,"Worker","WORKER",null,UUID.randomUUID(),"worker"),UUID.randomUUID());
        assertThrows(ResponseStatusException.class,()->guard.preHandle(request("GET","/api/investigations/"+resource+"/patch-reviews/"+UUID.randomUUID()+"/export"),new MockHttpServletResponse(),handler(PatchReviewController.class,"export")));
        actor("OWNER");assertThrows(ResponseStatusException.class,()->guard.preHandle(request("POST","/api/queue/claim"),new MockHttpServletResponse(),handler(DurableQueueController.class,"claim")));
    }
    static class Unclassified {public void operation(){}}
    @Test void unclassifiedHandlerFailsClosed()throws Exception {actor("OWNER");assertThrows(ResponseStatusException.class,()->new TeamPermissionInterceptor(access).preHandle(request("GET","/api/future"),new MockHttpServletResponse(),handler(Unclassified.class,"operation")));}
}
