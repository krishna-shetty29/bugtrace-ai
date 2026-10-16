package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}/java-index")
public class JavaIndexController {
    private final JavaIndexService service;
    public JavaIndexController(JavaIndexService service) { this.service=service; }
    @PostMapping public Map<String,Object> create(@PathVariable("id") UUID id) { return service.create(id); }
    @GetMapping public Map<String,Object> get(@PathVariable("id") UUID id) { return service.get(id); }
}
