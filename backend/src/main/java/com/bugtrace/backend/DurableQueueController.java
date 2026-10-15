package com.bugtrace.backend;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.bugtrace.backend.QueueContracts.*;

@RestController
@RequestMapping("/api")
public class DurableQueueController {
    private final DurableQueueService service;
    public DurableQueueController(DurableQueueService service) { this.service=service; }
    @PostMapping("/investigations/{id}/executions")
    public ResponseEntity<Map<String,Object>> create(@PathVariable("id") UUID id,@Valid @RequestBody CreateJob job) {
        return ResponseEntity.accepted().body(service.enqueue(id,job));
    }
    @GetMapping("/executions/{id}") public Map<String,Object> get(@PathVariable("id") UUID id) { return service.view(id); }
    @GetMapping("/executions/{id}/logs") public Map<String,Object> logs(@PathVariable("id") UUID id) { return service.logs(id); }
    @PostMapping("/queue/claim") public ResponseEntity<Map<String,Object>> claim(@Valid @RequestBody Claim claim) {
        var job=service.claim(claim); return job==null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }
    @PostMapping("/queue/{id}/heartbeat") public Map<String,String> beat(@PathVariable("id") UUID id,@Valid @RequestBody Heartbeat beat) {
        service.heartbeat(id,beat); return Map.of("status","RENEWED");
    }
    @PostMapping("/queue/{id}/complete") public Map<String,Object> complete(@PathVariable("id") UUID id,@Valid @RequestBody Complete completion) {
        return service.complete(id,completion);
    }
}
