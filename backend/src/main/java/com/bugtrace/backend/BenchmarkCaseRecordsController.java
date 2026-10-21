package com.bugtrace.backend;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations/{investigation}/benchmark-reproduction/{run}/records")
public class BenchmarkCaseRecordsController {
    private final BenchmarkCaseRecordsService records;
    public BenchmarkCaseRecordsController(BenchmarkCaseRecordsService records){this.records=records;}
    @PostMapping("/export") public Map<String,Object> export(@PathVariable UUID investigation,@PathVariable UUID run){return records.export(investigation,run);}
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID investigation,@PathVariable UUID run){return records.list(investigation,run);}
    @GetMapping("/{record}") public Map<String,Object> get(@PathVariable UUID investigation,@PathVariable UUID run,@PathVariable UUID record){return records.get(investigation,run,record);}
    @PostMapping("/search") public Map<String,Object> search(@PathVariable UUID investigation,@PathVariable UUID run,@RequestBody BenchmarkCaseRecordsService.Search request){return records.retrieve(investigation,run,request);}
    @PostMapping("/evidence") public List<AiEvidenceService.Evidence> evidence(@PathVariable UUID investigation,@PathVariable UUID run,@RequestBody BenchmarkCaseRecordsService.EvidenceRequest request){return records.resolve(investigation,run,request);}
    @GetMapping("/calls/{call}") public Map<String,Object> call(@PathVariable UUID investigation,@PathVariable UUID run,@PathVariable UUID call){return records.modelCall(investigation,run,call);}
    @GetMapping("/sources/{version}") public Map<String,Object> source(@PathVariable UUID investigation,@PathVariable UUID run,@PathVariable int version){return records.source(investigation,run,version);}
}
