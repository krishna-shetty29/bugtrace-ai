package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/investigations/{id}/benchmark-reproduction/{run}")
public class BenchmarkExecutionController {
    private final BenchmarkExecutionService service;
    public BenchmarkExecutionController(BenchmarkExecutionService service){this.service=service;}
    @GetMapping("/experiments") public List<Map<String,Object>> list(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.list(id,run);}
    @PostMapping("/experiments") public Map<String,Object> execute(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody BenchmarkExecutionService.Execute input){return service.execute(id,run,input);}
    @GetMapping("/experiments/{execution}") public Map<String,Object> get(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@PathVariable("execution") UUID execution){return service.get(id,run,execution);}
    @PostMapping("/compiler-repair") public Map<String,Object> repair(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody BenchmarkExecutionService.Repair input){return service.repair(id,run,input);}
}
