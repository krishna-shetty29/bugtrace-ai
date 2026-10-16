package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}/ai")
public class AiCallController {
    private final AiCallService service;
    public AiCallController(AiCallService service){this.service=service;}
    @GetMapping("/configuration") public Map<String,Object> configuration(){return service.configuration();}
    @GetMapping("/evidence") public List<AiEvidenceService.Evidence> evidence(@PathVariable("id") UUID id){return service.evidence(id);}
    @GetMapping("/calls") public List<Map<String,Object>> list(@PathVariable("id") UUID id){return service.list(id);}
    @GetMapping("/calls/{call}") public Map<String,Object> get(@PathVariable("id") UUID id,@PathVariable("call") UUID call){return service.get(id,call);}
    @PostMapping("/calls") public Map<String,Object> submit(@PathVariable("id") UUID id,@RequestBody AiCallService.Submission submission){return service.submit(id,submission);}
}
