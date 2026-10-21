package com.bugtrace.backend;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_TEAM_ACCESS_PG_TEST",matches="true")
class TeamAccessPostgresTest {
    @AfterEach void clear(){TeamAccessContext.clear();}
    @Test void roleHttpBoundariesRevocationSessionsProjectIsolationAndAuditPersistence()throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Local PostgreSQL required");
        String user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD"),schema="team_access_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);
                try(var in=new ClassPathResource("db/migration/V2__create_initial_tables.sql").getInputStream()){statement.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
                for(String name:List.of("V3__add_snapshot_import_metadata.sql","V4__add_durable_execution_queue.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+name));
                try(var in=new ClassPathResource("db/migration/V24__add_project_access_and_audit.sql").getInputStream()){statement.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
                var data=new DriverManagerDataSource(url,user,password);var properties=new Properties();properties.setProperty("currentSchema",schema);data.setConnectionProperties(properties);var jdbc=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);
                var investigations=new InvestigationService(jdbc);var p1=investigations.createProject("One","Private one");var p2=investigations.createProject("Two","Private two");
                String bootstrap="Z".repeat(43);var access=new TeamAccessService(jdbc,manager,bootstrap);var keys=access.bootstrap(bootstrap);assertEquals(2,keys.size());assertTrue(access.initialized());assertThrows(Exception.class,()->access.bootstrap(bootstrap));
                String ownerKey=keys.stream().filter(k->p1.id().equals(k.get("projectId"))).findFirst().orElseThrow().get("accessKey").toString();var owner=access.authenticate(ownerKey);TeamAccessContext.set(owner,UUID.randomUUID());
                assertEquals(1,investigations.listProjects().size());assertEquals(p1.id(),investigations.listProjects().getFirst().id());
                var inv=investigations.createInvestigation(p1.id(),"public","a".repeat(40),"One","Bug","Expected","Observed");
                TeamAccessContext.clear();var foreign=investigations.createInvestigation(p2.id(),"public","a".repeat(40),"Two","Bug","Expected","Observed");TeamAccessContext.set(owner,UUID.randomUUID());
                var investigator=access.issue(new TeamAccessService.Issue("Investigator","INVESTIGATOR",null,null,1));var reviewer=access.issue(new TeamAccessService.Issue("Reviewer","REVIEWER",null,null,1));var viewer=access.issue(new TeamAccessService.Issue("Viewer","VIEWER",null,null,1));
                assertThrows(Exception.class,()->access.issue(new TeamAccessService.Issue("Foreign worker",null,foreign.snapshotId(),"worker-one",1)));
                var worker=access.issue(new TeamAccessService.Issue("Worker",null,inv.snapshotId(),"worker-one",1));
                assertThrows(Exception.class,()->access.revoke(owner.id()));
                String viewerKey=viewer.get("accessKey").toString(),reviewerKey=reviewer.get("accessKey").toString(),investigatorKey=investigator.get("accessKey").toString(),workerKey=worker.get("accessKey").toString();
                var viewerSession=access.login(viewerKey);assertNotEquals(viewerKey,viewerSession.get("token"));assertEquals("VIEWER",access.session(viewerSession.get("token").toString()).actor().role());
                UUID ownExecution=UUID.randomUUID(),otherExecution=UUID.randomUUID();jdbc.update("INSERT INTO executions(id,investigation_id,command) VALUES (?,?,'test'),(?,?,'test')",ownExecution,inv.id(),otherExecution,foreign.id());
                var queue=mock(DurableQueueService.class);when(queue.claim(any())).thenReturn(null);when(queue.logs(ownExecution)).thenReturn(Map.of("stdout","own log","stderr",""));
                var patches=mock(PatchReviewService.class);UUID reviewId=UUID.randomUUID();when(patches.decide(eq(inv.id()),eq(reviewId),any())).thenReturn(Map.of("state","APPROVED"));
                String decisionBody=JsonMapper.builder().build().writeValueAsString(new PatchReviewService.Decision("APPROVE","b".repeat(64),"Permission boundary test",true,true,true,true,false));
                var filter=new TeamAccessFilter(access,"http://127.0.0.1:5173");var interceptor=new TeamPermissionInterceptor(access);
                var mvc=MockMvcBuilders.standaloneSetup(new InvestigationController(investigations),new DurableQueueController(queue),new PatchReviewController(patches),new TeamAccessController(access)).addFilters(filter).addInterceptors(interceptor).build();
                TeamAccessContext.clear();
                mvc.perform(get("/api/executions/"+ownExecution+"/logs")).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/executions/"+ownExecution+"/logs").header("Authorization","Bearer "+viewerKey)).andExpect(status().isOk());
                mvc.perform(get("/api/executions/"+otherExecution+"/logs").header("Authorization","Bearer "+viewerKey)).andExpect(status().isForbidden());
                mvc.perform(get("/api/investigations/"+foreign.id()).header("Authorization","Bearer "+ownerKey)).andExpect(status().isForbidden());
                mvc.perform(post("/api/investigations/"+inv.id()+"/patch-reviews/"+reviewId+"/decision").header("Authorization","Bearer "+investigatorKey).contentType(MediaType.APPLICATION_JSON).content(decisionBody)).andExpect(status().isForbidden());
                mvc.perform(post("/api/investigations/"+inv.id()+"/patch-reviews/"+reviewId+"/decision").header("Authorization","Bearer "+viewerKey).header("X-Role","OWNER").contentType(MediaType.APPLICATION_JSON).content(decisionBody)).andExpect(status().isForbidden());
                mvc.perform(post("/api/investigations/"+inv.id()+"/executions").header("Authorization","Bearer "+reviewerKey).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
                mvc.perform(get("/api/investigations/"+inv.id()+"/patch-reviews/"+reviewId+"/download/diff").header("Authorization","Bearer "+workerKey)).andExpect(status().isForbidden());
                mvc.perform(post("/api/queue/claim").header("Authorization","Bearer "+ownerKey).contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-one\",\"snapshotId\":\""+inv.snapshotId()+"\"}")).andExpect(status().isForbidden());
                mvc.perform(post("/api/queue/claim").header("Authorization","Bearer "+workerKey).contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-one\",\"snapshotId\":\""+foreign.snapshotId()+"\"}")).andExpect(status().isForbidden());
                mvc.perform(post("/api/queue/claim").header("Authorization","Bearer "+workerKey).contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-one\",\"snapshotId\":\""+inv.snapshotId()+"\"}")).andExpect(status().isNoContent());
                var login=mvc.perform(post("/api/access/login").header("Origin","http://127.0.0.1:5173").contentType(MediaType.APPLICATION_JSON).content("{\"accessKey\":\""+reviewerKey+"\"}")).andExpect(status().isOk()).andReturn();
                String setCookie=login.getResponse().getHeader("Set-Cookie");assertTrue(setCookie.contains("HttpOnly"));assertTrue(setCookie.contains("SameSite=Strict"));String cookie=setCookie.split(";",2)[0].split("=",2)[1];var session=JsonMapper.builder().build().readTree(login.getResponse().getContentAsString());String csrf=session.path("csrf").asText();
                String decision="/api/investigations/"+inv.id()+"/patch-reviews/"+reviewId+"/decision";
                mvc.perform(post(decision).cookie(new Cookie(TeamAccessFilter.COOKIE,cookie)).header("Origin","http://127.0.0.1:5173").contentType(MediaType.APPLICATION_JSON).content(decisionBody)).andExpect(status().isForbidden());
                mvc.perform(post(decision).cookie(new Cookie(TeamAccessFilter.COOKIE,cookie)).header("Origin","http://127.0.0.1:5173").header("X-BugTrace-CSRF",csrf).contentType(MediaType.APPLICATION_JSON).content(decisionBody)).andExpect(status().isOk());
                verify(patches,times(1)).decide(eq(inv.id()),eq(reviewId),any());verify(queue,times(1)).claim(any());
                TeamAccessContext.set(access.authenticate(viewerKey),UUID.randomUUID());assertThrows(Exception.class,()->access.issue(new TeamAccessService.Issue("Escalate","OWNER",null,null,1)));assertThrows(Exception.class,()->investigations.getInvestigation(foreign.id()));
                TeamAccessContext.set(owner,UUID.randomUUID());access.revoke((UUID)viewer.get("credentialId"));assertThrows(Exception.class,()->access.authenticate(viewerKey));assertThrows(Exception.class,()->access.session(viewerSession.get("token").toString()));
                var restarted=new TeamAccessService(jdbc,manager,bootstrap);assertEquals(owner.id(),restarted.authenticate(ownerKey).id());assertThrows(Exception.class,()->restarted.authenticate(viewerKey));
                assertFalse(restarted.audit().isEmpty());String trail=JsonMapper.builder().build().writeValueAsString(restarted.audit());assertFalse(trail.contains(ownerKey));assertFalse(trail.contains(viewerKey));assertTrue(trail.contains("DENIED"));
                assertThrows(Exception.class,()->jdbc.update("DELETE FROM team_audit_events"));assertThrows(Exception.class,()->jdbc.update("UPDATE team_audit_events SET outcome='fake'"));
                jdbc.update("UPDATE team_credentials SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",investigator.get("credentialId"));assertThrows(Exception.class,()->access.authenticate(investigatorKey));
                access.logout(cookie);assertThrows(Exception.class,()->access.session(cookie));
            }finally {TeamAccessContext.clear();statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
