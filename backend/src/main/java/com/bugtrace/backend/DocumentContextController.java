package com.bugtrace.backend;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{id}")
public class DocumentContextController {
    private final DocumentContextService service;
    public DocumentContextController(DocumentContextService service){this.service=service;}
    @GetMapping("/context-sources") public Map<String,Object> options(@PathVariable("id") UUID id){return service.options(id);}
    @GetMapping("/context-index") public Map<String,Object> status(@PathVariable("id") UUID id){return service.status(id);}
    @PostMapping("/context-index") public Map<String,Object> build(@PathVariable("id") UUID id,@RequestBody DocumentContextService.Selection selection){return service.build(id,selection);}
    @GetMapping("/context") public DocumentContextService.Context retrieve(@PathVariable("id") UUID id,@RequestParam("q") String query,
        @RequestParam(value="history",defaultValue="false") boolean history,@RequestParam(value="summaries",defaultValue="false") boolean summaries,@RequestParam(value="semantic",defaultValue="true") boolean semantic){return service.retrieve(id,query,history,summaries,semantic);}
    @GetMapping("/context-citations/{index}/{chunk}") public DocumentContextService.Reference citation(@PathVariable("id") UUID id,@PathVariable("index") UUID index,@PathVariable("chunk") int chunk){return service.citation(id,index,chunk);}
}
