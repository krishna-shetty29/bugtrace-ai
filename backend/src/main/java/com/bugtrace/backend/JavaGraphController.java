package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}/java-graph")
public class JavaGraphController {
    private final JavaGraphService service;
    public JavaGraphController(JavaGraphService service) { this.service=service; }
    @PostMapping public Map<String,Object> create(@PathVariable("id") UUID id) { return service.create(id); }
    @GetMapping public Map<String,Object> get(@PathVariable("id") UUID id) { return service.get(id); }
    @GetMapping("/impact") public JavaImpactTraversal.Impact impact(@PathVariable("id") UUID id,
        @RequestParam("nodeId") String nodeId,@RequestParam(value="depth",defaultValue="3") int depth,
        @RequestParam(value="includeUncertain",defaultValue="true") boolean includeUncertain) {
        return service.impact(id,nodeId,depth,includeUncertain);
    }
}
