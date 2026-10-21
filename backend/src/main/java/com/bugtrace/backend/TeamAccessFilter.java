package com.bugtrace.backend;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Component @Order(-1000)
public class TeamAccessFilter extends OncePerRequestFilter {
    static final String COOKIE="BUGTRACE_SESSION";
    private final TeamAccessService access;private final String origin;
    private final JsonMapper json=JsonMapper.builder().build();
    public TeamAccessFilter(TeamAccessService access,@Value("${BUGTRACE_BROWSER_ORIGIN:http://127.0.0.1:5173}") String origin){this.access=access;this.origin=origin;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getRequestURI();
        if(!path.startsWith("/api/")){if(path.equals("/actuator/health")){chain.doFilter(request,response);return;}response.sendError(404);return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");
        UUID trace=UUID.randomUUID();response.setHeader("X-BugTrace-Request",trace.toString());
        boolean publicRoute=Set.of("/api/health","/api/hello","/api/access/status","/api/access/login","/api/access/bootstrap").contains(path);
        try {
            if(path.contains("%")||path.contains(";")||path.contains("\\")||path.contains("//")||path.contains(".."))throw TeamAccessService.denied("Noncanonical API path");
            if(request.getHeader("Origin")!=null&&!origin.equals(request.getHeader("Origin")))throw TeamAccessService.denied("Browser origin is not approved");
            TeamAccessContext.Actor actor=null;boolean cookieAuth=false;String csrf=null;
            String header=request.getHeader("Authorization");String cookie=cookie(request);
            if(!publicRoute) {
                if(header!=null) {if(!header.startsWith("Bearer "))throw TeamAccessService.unauthenticated();actor=access.authenticate(header.substring(7));}
                else {var session=access.session(cookie);actor=session.actor();csrf=session.csrf();cookieAuth=true;}
            }
            TeamAccessContext.set(actor,trace);
            boolean mutation=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
            if(cookieAuth&&mutation&&(request.getHeader("Origin")==null||!constant(csrf,request.getHeader("X-BugTrace-CSRF"))))throw TeamAccessService.denied("Valid session CSRF token and origin required");
            TeamAccessContext.set(actor,trace);
            HttpServletRequest wrapped=request;
            if(mutation) {
                if(request.getContentType()!=null&&!request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))throw TeamAccessService.denied("Use JSON API requests");
                int maximum=actor!=null&&"WORKER".equals(actor.kind())?8*1024*1024:1024*1024;
                byte[] bytes=request.getInputStream().readNBytes(maximum+1);if(bytes.length>maximum)throw TeamAccessService.denied("API request body exceeds limit");
                wrapped=new Body(request,bytes);
            }
            request.setAttribute("team.trace",trace);wrapped.setAttribute("team.trace",trace);
            if(Set.of("/api/access/login","/api/access/bootstrap").contains(path))access.throttle(request.getRemoteAddr());
            chain.doFilter(wrapped,response);
        }catch(ResponseStatusException denied) {
            try {access.event(TeamAccessContext.actor(),trace,"AUTH_REJECTED","API_REQUEST","DENIED",denied.getStatusCode().value());}catch(Exception unavailable){/* No business handler has been authorized by this rejection. */}
            response.setStatus(denied.getStatusCode().value());response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(Map.of("code","ACCESS_DENIED","message",Objects.toString(denied.getReason(),"Access denied"),"requestId",trace)));
        }finally {TeamAccessContext.clear();}
    }
    static boolean constant(String expected,String actual){return expected!=null&&actual!=null&&actual.length()<=100&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));}
    static String cookie(HttpServletRequest request){if(request.getCookies()!=null)for(Cookie c:request.getCookies())if(COOKIE.equals(c.getName()))return c.getValue();return null;}
    static final class Body extends HttpServletRequestWrapper {
        final byte[] bytes;Body(HttpServletRequest request,byte[] bytes){super(request);this.bytes=bytes;}
        @Override public ServletInputStream getInputStream(){var stream=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return stream.read();}public boolean isFinished(){return stream.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Blocking JSON requests only");}};}
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
