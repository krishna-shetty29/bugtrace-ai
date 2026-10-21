package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/benchmark-registrations/{registrationId}/snapshots")
public class BenchmarkSnapshotController {
    private final BenchmarkSnapshotService service;
    public BenchmarkSnapshotController(BenchmarkSnapshotService service){this.service=service;}
    @PostMapping public Map<String,Object> capture(@PathVariable("registrationId") UUID id,@RequestBody BenchmarkSnapshotService.Import request){return service.capture(id,request);}
    @PostMapping("/{investigationId}/verify") public Map<String,Object> verify(@PathVariable("registrationId") UUID registration,@PathVariable("investigationId") UUID investigation){return service.verify(registration,investigation);}
}
