package com.bugtrace.backend;

import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SnapshotSearchService {
    private final JdbcTemplate jdbc;
    private final SnapshotImportService snapshots;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final BenchmarkRecordScope benchmarkScope;
    public SnapshotSearchService(JdbcTemplate jdbc,SnapshotImportService snapshots) {this(jdbc,snapshots,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public SnapshotSearchService(JdbcTemplate jdbc,SnapshotImportService snapshots,BenchmarkRecordScope scope) {this.jdbc=jdbc;this.snapshots=snapshots;this.benchmarkScope=scope;}
    public record Results(UUID investigationId,UUID snapshotId,String contentHash,String query,String kind,
        int limit,long totalMatches,boolean truncated,boolean symbolsAvailable,int failedJavaFiles,
        int sourceFiles,int textFiles,int executionsSearched,int executionWindow,List<SnapshotSearchEngine.Notice> notices,
        List<SnapshotSearchEngine.Hit> hits) {}
    public Results search(UUID investigation,String text,SnapshotSearchEngine.Kind kind,int limit) {
        SnapshotSearchEngine.Query query;
        try { query=SnapshotSearchEngine.Query.create(text,kind,limit); }
        catch(IllegalArgumentException exception){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,exception.getMessage());}
        var benchmark=benchmarkScope==null?null:benchmarkScope.optional(investigation);
        var verified=snapshots.verifySnapshot(investigation);
        var rows=jdbc.queryForList("SELECT s.id,s.source_location FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");
        UUID snapshot=(UUID)rows.getFirst().get("id");String hash=verified.get("contentHash").toString();
        try {
            var saved=benchmark==null?jdbc.queryForList("SELECT document::text AS document FROM java_symbol_indexes WHERE snapshot_id=? AND content_hash=?",snapshot,hash):List.<Map<String,Object>>of();
            JavaSymbolIndexer.Index index=saved.isEmpty()?null:mapper.readValue(saved.getFirst().get("document").toString(),JavaSymbolIndexer.Index.class);
            if(benchmark!=null)index=new JavaSymbolIndexer().index(benchmark.base());
            if(index==null&&(kind==SnapshotSearchEngine.Kind.CLASS||kind==SnapshotSearchEngine.Kind.METHOD))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Build the Java symbol index before searching classes or methods");
            var engine=new SnapshotSearchEngine();var matches=new SnapshotSearchEngine.Matches(limit);
            engine.symbols(index,query,matches);
            var scan=engine.source(Path.of(rows.getFirst().get("source_location").toString()),query,matches);
            int executions=0;
            if(benchmark==null&&query.includes(SnapshotSearchEngine.Kind.ERROR)) {
                var logs=jdbc.queryForList("""
                    SELECT j.id,left(j.stdout,2097152) AS stdout,left(j.stderr,2097152) AS stderr,j.technical_error
                    FROM execution_jobs j JOIN executions e ON e.id=j.id WHERE e.investigation_id=?
                        AND (j.stdout IS NOT NULL OR j.stderr IS NOT NULL OR j.technical_error IS NOT NULL)
                    ORDER BY j.created_at DESC,j.id LIMIT 10
                    """,investigation);
                executions=logs.size();
                for(var row:logs) {
                    String id=row.get("id").toString();
                    engine.log(id,"EXECUTION_STDOUT",(String)row.get("stdout"),query,matches);
                    engine.log(id,"EXECUTION_STDERR",(String)row.get("stderr"),query,matches);
                    engine.log(id,"EXECUTION_TECHNICAL_ERROR",(String)row.get("technical_error"),query,matches);
                }
            }
            if(!Objects.equals(hash,snapshots.verifySnapshot(investigation).get("contentHash")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Snapshot changed during search");
            return new Results(investigation,snapshot,hash,query.text(),kind.name(),limit,matches.totalMatches(),matches.totalMatches()>limit,index!=null,
                index==null?0:index.failedFiles(),scan.sourceFiles(),scan.textFiles(),executions,10,scan.notices(),matches.hits());
        }catch(ResponseStatusException exception){throw exception;}
        catch(Exception exception){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Search failed: "+exception.getMessage(),exception);}
    }
}
