package com.bugtrace.backend;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{investigation}/patch-reviews")
public class PatchReviewController {
    private final PatchReviewService service;
    public PatchReviewController(PatchReviewService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID investigation){return service.list(investigation);}
    @PostMapping public Map<String,Object> register(@PathVariable UUID investigation,@RequestBody PatchReviewService.Register input){return service.register(investigation,input);}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID investigation,@PathVariable UUID id){return service.get(investigation,id);}
    @PostMapping("/{id}/verify") public Map<String,Object> verify(@PathVariable UUID investigation,@PathVariable UUID id){return service.revalidate(investigation,id);}
    @PostMapping("/{id}/decision") public Map<String,Object> decision(@PathVariable UUID investigation,@PathVariable UUID id,@RequestBody PatchReviewService.Decision input){return service.decide(investigation,id,input);}
    @GetMapping("/{id}/download/{kind}") public ResponseEntity<byte[]> download(@PathVariable UUID investigation,@PathVariable UUID id,@PathVariable String kind){
        byte[] bytes=service.download(investigation,id,kind);String filename=switch(kind){case "diff"->"patch.diff";case "reproduction"->"GeneratedCheckoutReproductionTest.java";default->"verification-report.json";};
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString()).contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
    }
    @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID investigation,@PathVariable UUID id){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("patch-review-"+id+".zip").build().toString()).contentType(MediaType.APPLICATION_OCTET_STREAM).body(service.export(investigation,id));}
}
