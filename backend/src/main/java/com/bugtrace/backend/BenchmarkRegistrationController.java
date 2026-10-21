package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/benchmark-registrations")
public class BenchmarkRegistrationController {
    private final BenchmarkRegistrationService service;
    public BenchmarkRegistrationController(BenchmarkRegistrationService service){this.service=service;}
    @GetMapping public Map<String,Object> list(){return service.available();}
    @PostMapping public Map<String,Object> register(@RequestBody BenchmarkRegistrationService.Create input){return service.register(input);}
    @PostMapping("/{id}/verify") public Map<String,Object> verify(@PathVariable("id") UUID id){return service.verify(id);}
}
