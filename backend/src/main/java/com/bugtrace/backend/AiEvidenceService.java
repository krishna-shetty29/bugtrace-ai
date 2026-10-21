package com.bugtrace.backend;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiEvidenceService {
    public record Ref(String kind,UUID indexId,String chunkKey,Integer chunkNumber){}
    public record Evidence(String id,Ref reference,String origin,String sourceRef,String revision,String lineKind,
        int startLine,int endLine,String sourceHash,String text){}
    private final JdbcTemplate jdbc;private final SnapshotImportService snapshots;private final DocumentContextService documents;
    private final BenchmarkRecordScope benchmarkScope;
    public AiEvidenceService(JdbcTemplate jdbc,SnapshotImportService snapshots,DocumentContextService documents){this(jdbc,snapshots,documents,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public AiEvidenceService(JdbcTemplate jdbc,SnapshotImportService snapshots,DocumentContextService documents,BenchmarkRecordScope scope){this.jdbc=jdbc;this.snapshots=snapshots;this.documents=documents;this.benchmarkScope=scope;}
    public List<Evidence> available(UUID investigation){
        var benchmark=benchmarkScope==null?null:benchmarkScope.optional(investigation);
        String hash=snapshots.verifySnapshot(investigation).get("contentHash").toString();var list=new ArrayList<Evidence>();
        var indexes=jdbc.queryForList("SELECT s.id FROM semantic_indexes s JOIN investigations i ON i.snapshot_id=s.snapshot_id WHERE i.id=? AND s.content_hash=? AND s.chunker_version=? AND s.state IN ('COMPLETE','PARTIAL') ORDER BY s.created_at DESC,s.id LIMIT 1",investigation,hash,SemanticChunks.VERSION);
        if(!indexes.isEmpty()){
            UUID index=(UUID)indexes.getFirst().get("id");for(var row:jdbc.queryForList("SELECT chunk_key FROM semantic_chunks WHERE index_id=? ORDER BY file_path,start_line,end_line LIMIT 80",index))list.add(resolveOne(investigation,hash,new Ref("CODE",index,row.get("chunk_key").toString(),null)));
        }
        if(benchmark==null) {
        var contexts=jdbc.queryForList("SELECT id FROM context_indexes WHERE investigation_id=? AND content_hash=? AND algorithm_version=? AND state='COMPLETE' ORDER BY created_at DESC,id LIMIT 1",investigation,hash,ContextDocuments.VERSION);
        if(!contexts.isEmpty()){
            UUID index=(UUID)contexts.getFirst().get("id");for(var row:jdbc.queryForList("SELECT chunk_number FROM context_chunks WHERE index_id=? ORDER BY origin,source_ref,start_line LIMIT 80",index))list.add(resolveOne(investigation,hash,new Ref("CONTEXT",index,null,((Number)row.get("chunk_number")).intValue())));
        }
        }
        if(benchmark!=null)for(var item:list)BenchmarkRecordScope.validateEvidence(benchmark,item);
        return List.copyOf(list);
    }
    public List<Evidence> resolve(UUID investigation,List<Ref> references){
        if(references==null||references.isEmpty()||references.size()>8)throw bad("Select 1 to 8 saved evidence items");
        var benchmark=benchmarkScope==null?null:benchmarkScope.optional(investigation);
        if(benchmark!=null&&references.stream().anyMatch(ref->ref==null||!"CODE".equals(ref.kind())))throw bad("Benchmark model evidence permits registered case code only");
        String hash=snapshots.verifySnapshot(investigation).get("contentHash").toString();var list=new ArrayList<Evidence>();var ids=new HashSet<String>();int size=0;
        for(var ref:references){var evidence=resolveOne(investigation,hash,ref);if(benchmark!=null)BenchmarkRecordScope.validateEvidence(benchmark,evidence);if(!ids.add(evidence.id()))throw bad("Duplicate evidence item");list.add(evidence);size+=evidence.text().length();}
        if(size>12000)throw bad("Selected evidence exceeds 12000 characters");
        return List.copyOf(list);
    }
    private Evidence resolveOne(UUID investigation,String hash,Ref ref){
        if(ref==null||ref.indexId()==null||ref.kind()==null)throw bad("Invalid evidence reference");
        if(ref.kind().equals("CODE")){
            if(ref.chunkKey()==null||ref.chunkKey().length()>2000)throw bad("Invalid code chunk key");
            var rows=jdbc.queryForList("""
                SELECT c.*,r.commit_sha FROM semantic_chunks c JOIN semantic_indexes s ON s.id=c.index_id
                JOIN investigations i ON i.snapshot_id=s.snapshot_id JOIN repository_snapshots r ON r.id=i.snapshot_id
                WHERE i.id=? AND c.index_id=? AND c.chunk_key=? AND s.content_hash=? AND s.state IN ('COMPLETE','PARTIAL')
                """,investigation,ref.indexId(),ref.chunkKey(),hash);
            if(rows.isEmpty())throw bad("Code evidence does not belong to this verified snapshot");var row=rows.getFirst();String text=row.get("source_text").toString();
            if(!ContextDocuments.hash(text).equals(row.get("text_hash")))throw bad("Code evidence hash mismatch");
            return new Evidence("CODE:"+ref.indexId()+":"+ref.chunkKey(),ref,"CURRENT_SNAPSHOT_CODE",row.get("file_path").toString(),row.get("commit_sha").toString(),"SOURCE_LINE",((Number)row.get("start_line")).intValue(),((Number)row.get("end_line")).intValue(),row.get("text_hash").toString(),text);
        }
        if(ref.kind().equals("CONTEXT")&&ref.chunkNumber()!=null&&ref.chunkNumber()>=0){
            var citation=documents.citation(investigation,ref.indexId(),ref.chunkNumber());
            Integer matches=jdbc.queryForObject("SELECT count(*) FROM context_indexes s JOIN investigations i ON i.id=s.investigation_id WHERE s.id=? AND i.id=? AND s.snapshot_id=i.snapshot_id AND s.content_hash=?",Integer.class,ref.indexId(),investigation,hash);
            if(matches==null||matches!=1)throw bad("Context evidence does not belong to this verified snapshot");
            return new Evidence("CONTEXT:"+ref.indexId()+":"+ref.chunkNumber(),ref,citation.origin(),citation.sourceRef(),citation.sourceRevision(),citation.lineKind(),citation.startLine(),citation.endLine(),citation.chunkHash(),citation.text());
        }
        throw bad("Evidence kind must be CODE or CONTEXT");
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
