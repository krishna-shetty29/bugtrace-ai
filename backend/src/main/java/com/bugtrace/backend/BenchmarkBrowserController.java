package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/benchmark-browser")
public class BenchmarkBrowserController {
    private final BenchmarkBrowserService service;
    public BenchmarkBrowserController(BenchmarkBrowserService service){this.service=service;}
    @GetMapping("/catalog") public Map<String,Object> catalog(){return service.catalog();}
    @GetMapping("/investigations") public List<Map<String,Object>> recent(){return service.recent();}
    @PostMapping("/investigations") public Map<String,Object> create(@RequestBody BenchmarkBrowserService.Create request){return service.create(request);}
    @GetMapping("/investigations/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
    @PostMapping("/investigations/{id}/import") public Map<String,Object> capture(@PathVariable UUID id){return service.capture(id);}
}
