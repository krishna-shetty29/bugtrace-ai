package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}")
public class SemanticRetrievalController {
    private final SemanticRetrievalService service;
    public SemanticRetrievalController(SemanticRetrievalService service){this.service=service;}
    @PostMapping("/semantic-index") public Map<String,Object> build(@PathVariable("id") UUID id){return service.build(id);}
    @GetMapping("/semantic-index") public Map<String,Object> status(@PathVariable("id") UUID id){return service.status(id);}
    @GetMapping("/retrieve") public SemanticRetrievalService.Retrieval retrieve(@PathVariable("id") UUID id,
        @RequestParam("q") String query,@RequestParam(value="limit",defaultValue="10") int limit,
        @RequestParam(value="semantic",defaultValue="true") boolean semantic,
        @RequestParam(value="neighbors",defaultValue="true") boolean neighbors){return service.retrieve(id,query,limit,semantic,neighbors);}
}
