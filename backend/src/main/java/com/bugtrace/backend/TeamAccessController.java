package com.bugtrace.backend;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/access")
public class TeamAccessController {
    public record Key(String accessKey) {} public record Bootstrap(String secret) {} public record Project(String name) {}
    private final TeamAccessService access;
    public TeamAccessController(TeamAccessService access){this.access=access;}
    @GetMapping("/status") public Map<String,Object> status(){return Map.of("initialized",access.initialized());}
    @PostMapping("/bootstrap") public List<Map<String,Object>> bootstrap(@RequestBody Bootstrap input){return access.bootstrap(input==null?null:input.secret());}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Key input,HttpServletRequest request,HttpServletResponse response) {
        var session=access.login(input==null?null:input.accessKey());
        response.addHeader("Set-Cookie",ResponseCookie.from(TeamAccessFilter.COOKIE,session.get("token").toString()).httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/api").maxAge(3600).build().toString());
        return Map.of("actor",session.get("actor"),"csrf",session.get("csrf"));
    }
    @GetMapping("/session") public Map<String,Object> session(HttpServletRequest request) {
        var actor=TeamAccessContext.actor();var session=access.session(TeamAccessFilter.cookie(request));return Map.of("actor",actor,"csrf",session.csrf());
    }
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest request,HttpServletResponse response) {
        access.logout(TeamAccessFilter.cookie(request));response.addHeader("Set-Cookie",ResponseCookie.from(TeamAccessFilter.COOKIE,"").httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/api").maxAge(0).build().toString());return Map.of("loggedOut",true);
    }
    @GetMapping("/credentials") public List<Map<String,Object>> credentials(){return access.credentials();}
    @PostMapping("/credentials") public Map<String,Object> issue(@RequestBody TeamAccessService.Issue input){return access.issue(input);}
    @PostMapping("/credentials/{id}/revoke") public Map<String,Object> revoke(@PathVariable UUID id){access.revoke(id);return Map.of("revoked",true);}
    @PostMapping("/projects") public Map<String,Object> createProject(@RequestBody Project input){return access.createProject(input==null?null:input.name());}
    @GetMapping("/audit") public List<Map<String,Object>> audit(){return access.audit();}
}
