package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{investigation}/loops")
public class InvestigationLoopController {
    private final InvestigationLoopService service;
    public InvestigationLoopController(InvestigationLoopService service){this.service=service;}
    @GetMapping("/settings") public Map<String,Object> settings(@PathVariable UUID investigation){return service.settings(investigation);}
    @PostMapping public Map<String,Object> create(@PathVariable UUID investigation,@RequestBody InvestigationLoopService.Create input){return service.create(investigation,input);}
    @GetMapping("/{loop}") public Map<String,Object> get(@PathVariable UUID investigation,@PathVariable UUID loop){return service.get(investigation,loop);}
    @PostMapping("/{loop}/step") public Map<String,Object> step(@PathVariable UUID investigation,@PathVariable UUID loop,@RequestBody InvestigationLoopService.Step input){return service.step(investigation,loop,input);}
}
