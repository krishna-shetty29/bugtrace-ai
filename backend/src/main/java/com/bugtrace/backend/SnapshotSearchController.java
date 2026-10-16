package com.bugtrace.backend;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}/search")
public class SnapshotSearchController {
    private final SnapshotSearchService service;
    public SnapshotSearchController(SnapshotSearchService service) { this.service=service; }
    @GetMapping public SnapshotSearchService.Results search(@PathVariable("id") UUID id,
        @RequestParam("q") String query,
        @RequestParam(value="kind",defaultValue="ALL") SnapshotSearchEngine.Kind kind,
        @RequestParam(value="limit",defaultValue="50") int limit) { return service.search(id,query,kind,limit); }
}
