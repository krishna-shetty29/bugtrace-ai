package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{investigation}/diagnoses")
public class DiagnosisController {
    private final DiagnosisService service;
    public DiagnosisController(DiagnosisService service){this.service=service;}
    @GetMapping("/settings") public Map<String,Object> choices(@PathVariable UUID investigation){return service.choices(investigation);}
    @PostMapping public Map<String,Object> create(@PathVariable UUID investigation,@RequestBody DiagnosisService.Create input){return service.create(investigation,input);}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID investigation,@PathVariable UUID id){return service.get(investigation,id);}
    @PostMapping("/{id}/verify") public Map<String,Object> verify(@PathVariable UUID investigation,@PathVariable UUID id){return service.verify(investigation,id);}
    @GetMapping("/{id}/citations/{number}") public Map<String,Object> cite(@PathVariable UUID investigation,@PathVariable UUID id,@PathVariable int number){return service.citation(investigation,id,number);}
}
