package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}/agent-runs")
public class AgentWorkflowController {
    private final AgentWorkflowService service;
    public AgentWorkflowController(AgentWorkflowService service){this.service=service;}
    public record Advance(int expectedRole) {}
    @GetMapping public List<Map<String,Object>> list(@PathVariable("id") UUID id){return service.list(id);}
    @GetMapping("/execution-options") public List<Map<String,Object>> options(@PathVariable("id") UUID id){return service.options(id);}
    @PostMapping public Map<String,Object> create(@PathVariable("id") UUID id,@RequestBody AgentWorkflowService.Create input){return service.create(id,input);}
    @GetMapping("/{run}") public Map<String,Object> get(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.get(id,run);}
    @PostMapping("/{run}/advance") public Map<String,Object> advance(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody Advance input){return service.advance(id,run,input.expectedRole());}
    @PostMapping("/{run}/finish-verification") public Map<String,Object> finish(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.finishVerification(id,run);}
    @PostMapping("/{run}/stop") public Map<String,Object> stop(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.stop(id,run);}
}
