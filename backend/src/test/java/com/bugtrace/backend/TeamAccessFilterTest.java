package com.bugtrace.backend;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TeamAccessFilterTest {
    private final TeamAccessService access=mock(TeamAccessService.class);
    private final TeamAccessFilter filter=new TeamAccessFilter(access,"http://127.0.0.1:5173");
    private final TeamAccessContext.Actor actor=new TeamAccessContext.Actor(UUID.randomUUID(),UUID.randomUUID(),"Person","HUMAN","REVIEWER",null,null);
    @AfterEach void clear(){assertNull(TeamAccessContext.actor());}
    private MockHttpServletRequest request(String method,String path){var r=new MockHttpServletRequest(method,path);r.setRequestURI(path);r.setContentType("application/json");return r;}
    @Test void anonymousApiIsRejectedBeforeHandler()throws Exception {
        when(access.session(null)).thenThrow(TeamAccessService.unauthenticated());var response=new MockHttpServletResponse();var reached=new AtomicBoolean();filter.doFilter(request("GET","/api/projects"),response,(r,s)->reached.set(true));assertEquals(401,response.getStatus());assertFalse(reached.get());
    }
    @Test void cookieMutationNeedsCsrfAndOrigin()throws Exception {
        when(access.session("cookie")).thenReturn(new TeamAccessService.Session(actor,"csrf"));
        for(boolean withOrigin:List.of(false,true)){var r=request("POST","/api/investigations/"+UUID.randomUUID()+"/patch-reviews");r.setCookies(new Cookie(TeamAccessFilter.COOKIE,"cookie"));if(withOrigin)r.addHeader("Origin","http://127.0.0.1:5173");var response=new MockHttpServletResponse();filter.doFilter(r,response,(a,b)->fail("Mutation must not run"));assertEquals(403,response.getStatus());}
    }
    @Test void validCookieMutationGetsServerActorAndContextIsCleared()throws Exception {
        when(access.session("cookie")).thenReturn(new TeamAccessService.Session(actor,"csrf"));var r=request("POST","/api/access/logout");r.setCookies(new Cookie(TeamAccessFilter.COOKIE,"cookie"));r.addHeader("Origin","http://127.0.0.1:5173");r.addHeader("X-BugTrace-CSRF","csrf");r.setContent("{}".getBytes());var reached=new AtomicBoolean();filter.doFilter(r,new MockHttpServletResponse(),(a,b)->{reached.set(true);assertEquals(actor,TeamAccessContext.actor());});assertTrue(reached.get());
    }
    @Test void foreignOriginAndEncodedPathsFailClosed()throws Exception {
        for(String path:List.of("/api/%2finvestigations","/api/investigations;admin=true","/api//projects")){var response=new MockHttpServletResponse();filter.doFilter(request("GET",path),response,(a,b)->fail("Noncanonical path"));assertEquals(403,response.getStatus());}
        var r=request("POST","/api/access/login");r.addHeader("Origin","https://attacker.invalid");var response=new MockHttpServletResponse();filter.doFilter(r,response,(a,b)->fail("Foreign origin"));assertEquals(403,response.getStatus());
    }
    @Test void invalidAuthorizationCannotFallBackToCookie()throws Exception {
        var r=request("GET","/api/projects");r.addHeader("Authorization","Basic attacker");r.setCookies(new Cookie(TeamAccessFilter.COOKIE,"cookie"));var response=new MockHttpServletResponse();filter.doFilter(r,response,(a,b)->fail("Invalid header"));assertEquals(401,response.getStatus());verify(access,never()).authenticate(anyString());verify(access,never()).session(any());
    }
    @Test void explicitBearerDoesNotUseAmbientCookieCsrf()throws Exception {
        String key="a".repeat(43);when(access.authenticate(key)).thenReturn(actor);var r=request("POST","/api/investigations");r.addHeader("Authorization","Bearer "+key);r.setContent("{}".getBytes());var reached=new AtomicBoolean();filter.doFilter(r,new MockHttpServletResponse(),(a,b)->{reached.set(true);assertEquals(actor,TeamAccessContext.actor());});assertTrue(reached.get());
    }
}
