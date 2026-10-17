package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}/agent-runs/{run}/reproduction")
public class ReproductionController {
    private final ReproductionService service;
    public ReproductionController(ReproductionService service){this.service=service;}
    @GetMapping public Map<String,Object> get(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.get(id,run);}
    @PostMapping("/generate") public Map<String,Object> generate(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody ReproductionService.Generate input){return service.generate(id,run,input,false);}
    @PostMapping("/repair") public Map<String,Object> repair(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody ReproductionService.Generate input){return service.generate(id,run,input,true);}
    @PostMapping("/execute") public Map<String,Object> execute(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody ReproductionService.Execute input){return service.execute(id,run,input);}
    @PostMapping("/abandon") public Map<String,Object> abandon(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.abandon(id,run);}
}
