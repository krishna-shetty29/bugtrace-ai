package com.bugtrace.backend;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SemanticRetrievalService {
    private final JdbcTemplate jdbc;private final SnapshotImportService snapshots;
    private final SnapshotSearchService search;private final JavaGraphService graphs;private final OllamaEmbeddings embeddings;
    private final TransactionTemplate tx;private final JsonMapper mapper=JsonMapper.builder().build();
    private final Set<UUID> building=ConcurrentHashMap.newKeySet();
    public SemanticRetrievalService(JdbcTemplate jdbc,SnapshotImportService snapshots,SnapshotSearchService search,
        JavaGraphService graphs,OllamaEmbeddings embeddings,PlatformTransactionManager transactions){
        this.jdbc=jdbc;this.snapshots=snapshots;this.search=search;this.graphs=graphs;this.embeddings=embeddings;tx=new TransactionTemplate(transactions);
    }
    public record Neighbor(String relation,String direction,String confidence,String reason,
        String anchor,HybridRanking.Candidate context) {}
    public record Retrieval(UUID investigationId,UUID snapshotId,String contentHash,String query,boolean semanticUsed,
        Object index,List<HybridRanking.Ranked> contexts,List<Neighbor> neighbors,List<String> notices) {}
    private record Source(UUID snapshot,String hash,Path base) {}
    private Source source(UUID investigation){
        var verified=snapshots.verifySnapshot(investigation);
        var rows=jdbc.queryForList("SELECT s.id,s.source_location FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");
        return new Source((UUID)rows.getFirst().get("id"),verified.get("contentHash").toString(),Path.of(rows.getFirst().get("source_location").toString()));
    }
    private void verify(UUID investigation,Source source){
        if(!Objects.equals(source.hash(),snapshots.verifySnapshot(investigation).get("contentHash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Snapshot changed during retrieval/indexing");
    }
    public Map<String,Object> status(UUID investigation){
        var source=source(investigation);
        return Map.of("snapshotId",source.snapshot(),"contentHash",source.hash(),"model",embeddings.model(),
            "indexes",jdbc.queryForList("SELECT id,model_name,model_digest,dimensions,chunker_version,state,chunk_count,diagnostics::text AS diagnostics,technical_error,created_at,finished_at FROM semantic_indexes WHERE snapshot_id=? AND content_hash=? ORDER BY created_at DESC,id LIMIT 5",source.snapshot(),source.hash()));
    }
    public Map<String,Object> build(UUID investigation){
        var source=source(investigation);
        if(!building.add(source.snapshot()))throw new ResponseStatusException(HttpStatus.CONFLICT,"An embedding build is already running for this snapshot");
        UUID id=UUID.randomUUID();boolean inserted=false;
        try{
            long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
            String digest=embeddings.digest();
            jdbc.update("INSERT INTO semantic_indexes(id,snapshot_id,content_hash,model_name,model_digest,dimensions,chunker_version,state) VALUES (?,?,?,?,?,768,?,'BUILDING')",id,source.snapshot(),source.hash(),embeddings.model(),digest,SemanticChunks.VERSION);
            inserted=true;
            var corpus=new SemanticChunks().read(source.base());var chunks=corpus.chunks();var vectors=new ArrayList<String>();
            for(int offset=0;offset<chunks.size();offset+=4){
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new IllegalStateException("Five minute embedding build deadline exceeded; use a smaller snapshot");
                var batch=chunks.subList(offset,Math.min(offset+4,chunks.size())).stream()
                    .map(chunk->{String title=chunk.filePath()+" "+chunk.name();return "title: "+title.substring(0,Math.min(title.length(),240))+" | text: "+chunk.text();}).toList();
                vectors.addAll(embeddings.embed(batch,Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(60).toNanos()))));
            }
            if(!digest.equals(embeddings.digest()))throw new IllegalStateException("Model changed during build; rebuild with a stable model");
            verify(investigation,source);
            String diagnostics=mapper.writeValueAsString(corpus.diagnostics());
            tx.executeWithoutResult(ignored->{
                for(int n=0;n<chunks.size();n++){
                    var chunk=chunks.get(n);
                    jdbc.update("""
                        INSERT INTO semantic_chunks(index_id,chunk_key,file_path,start_line,end_line,kind,symbol_name,is_test,source_text,text_hash,embedding)
                        VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS vector))
                        """,id,chunk.key(),chunk.filePath(),chunk.startLine(),chunk.endLine(),chunk.kind(),chunk.name(),chunk.isTest(),chunk.text(),chunk.textHash(),vectors.get(n));
                }
                jdbc.update("UPDATE semantic_indexes SET state=?,chunk_count=?,diagnostics=CAST(? AS jsonb),finished_at=clock_timestamp() WHERE id=?",
                    corpus.diagnostics().isEmpty()?"COMPLETE":"PARTIAL",chunks.size(),diagnostics,id);
            });
            return status(investigation);
        }catch(Exception ex){
            if(inserted)jdbc.update("UPDATE semantic_indexes SET state='FAILED',technical_error=?,finished_at=clock_timestamp() WHERE id=?",shortError(ex),id);
            if(ex instanceof ResponseStatusException response)throw response;
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Embedding build failed: "+shortError(ex),ex);
        }finally{building.remove(source.snapshot());}
    }
    public Retrieval retrieve(UUID investigation,String query,int limit,boolean useSemantic,boolean includeNeighbors){
        // Reuse existing validation and investigation-scoped log search.
        var exact=search.search(investigation,query,SnapshotSearchEngine.Kind.ALL,100);
        if(limit<1||limit>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limit must be 1 to 20");
        var source=source(investigation);var notices=new ArrayList<String>();
        exact.notices().forEach(notice->notices.add(notice.filePath()+": "+notice.message()));
        if(exact.truncated())notices.add("Lexical candidate pool is limited to the best 100 matches; refine broad queries");
        if(!exact.symbolsAvailable())notices.add("Build the Java symbol index to include symbol matches");
        var rows=jdbc.queryForList("SELECT id,model_name,model_digest,state,chunk_count FROM semantic_indexes WHERE snapshot_id=? AND content_hash=? AND chunker_version=? AND state IN ('COMPLETE','PARTIAL') ORDER BY created_at DESC,id LIMIT 1",source.snapshot(),source.hash(),SemanticChunks.VERSION);
        Map<String,Object> index=rows.isEmpty()?Map.of():rows.getFirst();
        List<SemanticChunks.Chunk> chunks=List.of();
        if(!rows.isEmpty())chunks=loadChunks((UUID)index.get("id"));
        var channels=new LinkedHashMap<String,List<HybridRanking.Candidate>>();
        var text=new ArrayList<HybridRanking.Candidate>();var symbols=new ArrayList<HybridRanking.Candidate>();
        for(var hit:exact.hits()){
            var candidate=fromHit(hit,chunks);
            ("SYMBOL".equals(hit.origin())?symbols:text).add(candidate);
        }
        channels.put("EXACT",text);channels.put("SYMBOL",symbols);boolean used=false;
        if(useSemantic){
            try{
                if(rows.isEmpty())throw new IllegalStateException("Build embeddings for this snapshot first");
                if(!embeddings.model().equals(index.get("model_name"))||!embeddings.digest().equals(index.get("model_digest")))
                    throw new IllegalStateException("Saved embeddings use a different model digest; rebuild them");
                String vector=embeddings.embed(List.of("task: search result | query: "+query),Duration.ofSeconds(30)).getFirst();
                if(!embeddings.digest().equals(index.get("model_digest")))throw new IllegalStateException("Model changed during query");
                var semantic=jdbc.query("SELECT chunk_key,1-(embedding <=> CAST(? AS vector)) AS similarity FROM semantic_chunks WHERE index_id=? ORDER BY embedding <=> CAST(? AS vector),chunk_key LIMIT 30",
                    (rs,n)->Map.entry(rs.getString("chunk_key"),rs.getDouble("similarity")),vector,index.get("id"),vector);
                Map<String,SemanticChunks.Chunk> byKey=new HashMap<>();chunks.forEach(chunk->byKey.put(chunk.key(),chunk));
                channels.put("SEMANTIC",semantic.stream().map(entry->candidate(byKey.get(entry.getKey()),entry.getValue())).toList());used=true;
                if("PARTIAL".equals(index.get("state")))notices.add("Semantic index is PARTIAL; inspect diagnostics for fallback or unreadable files");
            }catch(Exception ex){notices.add("Semantic retrieval unavailable: "+shortError(ex)+". Exact and symbol results remain available.");}
        }
        var ranked=HybridRanking.fuse(channels,limit);var neighbors=new ArrayList<Neighbor>();
        if(includeNeighbors){
            try{
                var graph=mapper.convertValue(graphs.get(investigation).get("graph"),JavaRelationshipAnalyzer.Graph.class);
                neighbors.addAll(neighbors(graph,ranked,chunks));
                notices.add("Graph neighbors are static candidates; POSSIBLE/UNRESOLVED edges do not establish runtime calls or test coverage");
            }catch(ResponseStatusException ex){if(ex.getStatusCode().value()!=404)throw ex;notices.add("Build the Java relationship graph to include nearby callers, callees and tests");}
        }
        verify(investigation,source);
        return new Retrieval(investigation,source.snapshot(),source.hash(),query,used,index,ranked,List.copyOf(neighbors),List.copyOf(notices));
    }
    private List<SemanticChunks.Chunk> loadChunks(UUID index){
        return jdbc.query("SELECT * FROM semantic_chunks WHERE index_id=? ORDER BY file_path,start_line,end_line",(rs,n)->new SemanticChunks.Chunk(rs.getString("chunk_key"),rs.getString("file_path"),rs.getInt("start_line"),rs.getInt("end_line"),rs.getString("kind"),rs.getString("symbol_name"),rs.getBoolean("is_test"),rs.getString("source_text"),rs.getString("text_hash")),index);
    }
    static HybridRanking.Candidate candidate(SemanticChunks.Chunk chunk,Double similarity){return new HybridRanking.Candidate(chunk.key(),chunk.filePath(),chunk.startLine(),chunk.endLine(),chunk.name(),chunk.text(),null,similarity);}
    static HybridRanking.Candidate fromHit(SnapshotSearchEngine.Hit hit,List<SemanticChunks.Chunk> chunks){
        if(hit.executionId()==null||hit.executionId().isBlank()){
            var nearest=chunks.stream().filter(chunk->chunk.filePath().equals(hit.filePath())&&chunk.startLine()<=hit.line()&&chunk.endLine()>=hit.line())
                .min(Comparator.comparingInt(chunk->chunk.endLine()-chunk.startLine()));
            if(nearest.isPresent())return candidate(nearest.get(),null);
        }
        String key=hit.origin()+":"+hit.executionId()+":"+hit.filePath()+":"+hit.line();
        return new HybridRanking.Candidate(key,hit.filePath(),Math.max(1,hit.line()),Math.max(1,hit.line()),hit.name(),hit.snippet(),hit.executionId(),null);
    }
    static List<Neighbor> neighbors(JavaRelationshipAnalyzer.Graph graph,List<HybridRanking.Ranked> ranked,List<SemanticChunks.Chunk> chunks){
        var nodes=new HashMap<String,JavaRelationshipAnalyzer.Vertex>();graph.nodes().forEach(node->nodes.put(node.id(),node));
        var results=new ArrayList<Neighbor>();var seen=new HashSet<String>();
        for(var hit:ranked.stream().limit(5).toList()){
            var context=hit.context();
            var anchors=graph.nodes().stream().filter(node->Set.of("METHOD","CONSTRUCTOR").contains(node.kind())&&Objects.equals(node.filePath(),context.filePath())&&node.startLine()<=context.endLine()&&node.endLine()>=context.startLine()).toList();
            for(var anchor:anchors)for(var edge:graph.edges()){
                if(!Set.of("CALLS","TEST_REFERENCES","OVERRIDES_OR_IMPLEMENTS","METHOD_REFERENCE").contains(edge.kind()))continue;
                boolean outgoing=edge.from().equals(anchor.id()),incoming=edge.to().equals(anchor.id());
                if(!outgoing&&!incoming)continue;
                var node=nodes.get(outgoing?edge.to():edge.from());if(node==null)continue;
                var chunk=chunks.stream().filter(c->Objects.equals(c.filePath(),node.filePath())&&c.startLine()<=node.startLine()&&c.endLine()>=node.startLine()).min(Comparator.comparingInt(c->c.endLine()-c.startLine()));
                if(chunk.isPresent()&&seen.add(edge.id()+":"+chunk.get().key()))results.add(new Neighbor(edge.kind(),outgoing?"CALLEE / REFERENCED":"CALLER / REFERENCING",edge.confidence(),edge.reason(),anchor.label(),candidate(chunk.get(),null)));
                if(results.size()>=15)return List.copyOf(results);
            }
        }
        return List.copyOf(results);
    }
    private static String shortError(Exception ex){String message=ex.getMessage();if(message==null)message=ex.getClass().getSimpleName();return message.substring(0,Math.min(message.length(),1000));}
}
