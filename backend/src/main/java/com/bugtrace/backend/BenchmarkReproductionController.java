package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/investigations/{id}/benchmark-reproduction")
public class BenchmarkReproductionController {
    private final BenchmarkReproductionService service;
    public BenchmarkReproductionController(BenchmarkReproductionService service){this.service=service;}
    @GetMapping public Map<String,Object> settings(@PathVariable("id") UUID id){return service.settings(id);}
    @PostMapping public Map<String,Object> create(@PathVariable("id") UUID id,@RequestBody BenchmarkReproductionService.Create input){return service.create(id,input);}
    @GetMapping("/{run}") public Map<String,Object> get(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.get(id,run);}
    @PostMapping("/{run}/advance") public Map<String,Object> advance(@PathVariable("id") UUID id,@PathVariable("run") UUID run,@RequestBody BenchmarkReproductionService.Step step){return service.advance(id,run,step);}
    @PostMapping("/{run}/stop") public Map<String,Object> stop(@PathVariable("id") UUID id,@PathVariable("run") UUID run){return service.stop(id,run);}
}
