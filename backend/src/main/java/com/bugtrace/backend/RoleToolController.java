package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/investigations/{id}/agent-runs/{run}")
public class RoleToolController {
    private final RoleToolGateway gateway;
    public RoleToolController(RoleToolGateway gateway){this.gateway=gateway;}
    @GetMapping("/tools") public List<Map<String,Object>> list(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return gateway.list(id,run);}
    @PostMapping("/tools") public Map<String,Object> request(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody JsonNode body){try{return gateway.manual(id,run,body);}catch(IllegalArgumentException ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,ex.getMessage());}}
    @PostMapping("/execution-permission") public Map<String,Object> permission(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody JsonNode body){
        try {
            RoleToolPolicy.fields(body,"enabled");
            if(!body.get("enabled").isBoolean())throw new IllegalArgumentException("enabled must be boolean");
            return gateway.permissions(id,run,body.get("enabled").asBoolean());
        }catch(IllegalArgumentException ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,ex.getMessage());}
    }
}
