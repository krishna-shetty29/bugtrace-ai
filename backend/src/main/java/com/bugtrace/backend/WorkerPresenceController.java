package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workers")
public class WorkerPresenceController {
 private final WorkerPresenceService service;
 public WorkerPresenceController(WorkerPresenceService service){this.service=service;}
 @PostMapping("/presence") public Map<String,Object> publish(@RequestBody WorkerPresenceService.Contact contact){return service.publish(contact);}
 @GetMapping("/presence") public List<Map<String,Object>> list(){return service.list();}
}
