package com.bugtrace.backend;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DocumentContextService {
    private final JdbcTemplate jdbc;private final SnapshotImportService snapshots;private final SemanticRetrievalService current;
    private final OllamaEmbeddings model;private final TransactionTemplate tx;private final Path repository;
    private final JsonMapper mapper=JsonMapper.builder().build();private final Set<UUID> building=ConcurrentHashMap.newKeySet();
    public DocumentContextService(JdbcTemplate jdbc,SnapshotImportService snapshots,SemanticRetrievalService current,
        OllamaEmbeddings model,PlatformTransactionManager transactions,@Value("${bugtrace.repository-root}") String repository){
        this.jdbc=jdbc;this.snapshots=snapshots;this.current=current;this.model=model;tx=new TransactionTemplate(transactions);this.repository=Path.of(repository).toAbsolutePath().normalize();
    }
    public record Selection(List<String> documentationPaths,List<String> commitIds,List<UUID> summaryInvestigationIds,boolean includeExecutionEvidence){
        public Selection{documentationPaths=documentationPaths==null?List.of():List.copyOf(documentationPaths);commitIds=commitIds==null?List.of():List.copyOf(commitIds);summaryInvestigationIds=summaryInvestigationIds==null?List.of():List.copyOf(summaryInvestigationIds);}
    }
    private record Source(UUID snapshot,String hash,String commit,String module,Path base,String repo){}
    public record Reference(UUID indexId,int chunkNumber,String origin,String sourceRef,String sourceRevision,String lineKind,
        int startLine,int endLine,String documentHash,String chunkHash,String title,String text){}
    public record Match(Reference reference,double score,Map<String,Integer> channels,Double cosineSimilarity){}
    public record Context(SemanticRetrievalService.Retrieval currentSnapshot,Object supplementaryIndex,boolean supplementarySemanticUsed,List<Match> supplementary,List<String> notices){}
    private Source source(UUID investigation){
        var verified=snapshots.verifySnapshot(investigation);
        var row=jdbc.queryForMap("SELECT s.id,s.commit_sha,s.module_path,s.source_location,s.repository_url FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
        return new Source((UUID)row.get("id"),verified.get("contentHash").toString(),row.get("commit_sha").toString(),Objects.toString(row.get("module_path"),"."),Path.of(row.get("source_location").toString()).toAbsolutePath().normalize(),row.get("repository_url").toString());
    }
    private void verify(UUID investigation,Source source){if(!source.hash().equals(snapshots.verifySnapshot(investigation).get("contentHash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Captured source changed while indexing context");}
    public Map<String,Object> options(UUID investigation){
        var source=source(investigation);var files=new ArrayList<Map<String,Object>>();
        try(var stream=Files.walk(source.base())){
            for(Path path:stream.limit(2002).sorted().toList()){
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                String name=source.base().relativize(path).toString().replace('\\','/');
                if(ContextDocuments.supported(name))files.add(Map.of("path",name,"bytes",Files.size(path)));
            }
        }catch(Exception ex){throw fail("Could not list captured documentation",ex);}
        return Map.of("snapshotId",source.snapshot(),"commit",source.commit(),"modulePath",source.module(),"files",files,
            "previousReports",jdbc.queryForList("SELECT id,title,updated_at FROM investigations WHERE snapshot_id=? AND created_at<(SELECT created_at FROM investigations WHERE id=?) ORDER BY created_at DESC LIMIT 20",source.snapshot(),investigation));
    }
    public Map<String,Object> status(UUID investigation){
        var source=source(investigation);
        return Map.of("snapshotId",source.snapshot(),"contentHash",source.hash(),"indexes",jdbc.queryForList("SELECT id,state,manifest,statistics,technical_error,created_at,finished_at FROM context_indexes WHERE investigation_id=? AND snapshot_id=? AND content_hash=? ORDER BY created_at DESC,id LIMIT 5",investigation,source.snapshot(),source.hash()).stream().map(this::jsonColumns).toList());
    }
    private Map<String,Object> jsonColumns(Map<String,Object> source){var row=new LinkedHashMap<>(source);for(String field:List.of("manifest","statistics"))if(row.get(field)!=null)row.put(field,mapper.readTree(row.get(field).toString()));return row;}
    public Map<String,Object> build(UUID investigation,Selection selection){
        if(selection.documentationPaths().size()>30||selection.commitIds().size()>5||selection.summaryInvestigationIds().size()>5)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select at most 30 documents, five commits and five previous reports");
        var source=source(investigation);if(!building.add(investigation))throw new ResponseStatusException(HttpStatus.CONFLICT,"Context indexing is already running for this investigation");
        UUID id=UUID.randomUUID();boolean inserted=false;
        try{
            long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
            var docs=collect(investigation,source,selection);
            var chunks=ContextDocuments.chunks(docs);if(chunks.isEmpty())throw new IllegalArgumentException("No non-empty selected sources. Select documentation, a module commit, a previous report or completed execution evidence");
            String digest=model.digest();
            jdbc.update("INSERT INTO context_indexes(id,investigation_id,snapshot_id,content_hash,model_digest,algorithm_version,state) VALUES (?,?,?,?,?,?,'BUILDING')",id,investigation,source.snapshot(),source.hash(),digest,ContextDocuments.VERSION);inserted=true;
            var embedded=new IncrementalEmbeddings(jdbc,model).embed(chunks.stream().map(ContextDocuments::prompt).toList(),digest,deadline);
            if(!digest.equals(model.digest()))throw new IllegalStateException("Model changed during indexing");verify(investigation,source);
            var manifest=docs.stream().map(doc->Map.of("origin",doc.origin(),"sourceRef",doc.sourceRef(),"revision",doc.revision(),"hash",ContextDocuments.hash(doc.body()))).toList();
            var previous=jdbc.queryForList("SELECT manifest::text AS manifest FROM context_indexes WHERE investigation_id=? AND state='COMPLETE' AND snapshot_id=? ORDER BY created_at DESC,id LIMIT 1",investigation,source.snapshot());
            var oldHashes=new HashMap<String,String>();
            if(!previous.isEmpty())for(var item:mapper.readTree(previous.getFirst().get("manifest").toString()))oldHashes.put(item.path("origin").asText()+":"+item.path("sourceRef").asText(),item.path("hash").asText());
            long unchanged=manifest.stream().filter(item->item.get("hash").equals(oldHashes.get(item.get("origin")+":"+item.get("sourceRef")))).count();
            var newKeys=new HashSet<String>();manifest.forEach(item->newKeys.add(item.get("origin")+":"+item.get("sourceRef")));
            long removed=oldHashes.keySet().stream().filter(key->!newKeys.contains(key)).count();
            String statistics=mapper.writeValueAsString(Map.of("documents",docs.size(),"chunks",chunks.size(),"unchangedDocuments",unchanged,"newOrChangedDocuments",docs.size()-unchanged,"removedDocuments",removed,"reusedEmbeddings",embedded.reused(),"generatedEmbeddings",embedded.generated()));
            String manifestJson=mapper.writeValueAsString(manifest);
            tx.executeWithoutResult(ignored->{
                for(int n=0;n<chunks.size();n++){
                    var chunk=chunks.get(n);jdbc.update("""
                        INSERT INTO context_chunks(index_id,chunk_number,origin,source_ref,source_revision,line_kind,start_line,end_line,document_hash,chunk_hash,title,source_text,embedding)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector))
                        """,id,n,chunk.origin(),chunk.sourceRef(),chunk.revision(),chunk.lineKind(),chunk.startLine(),chunk.endLine(),chunk.documentHash(),chunk.chunkHash(),chunk.title(),chunk.text(),embedded.vectors().get(n));
                }
                jdbc.update("UPDATE context_indexes SET state='COMPLETE',manifest=CAST(? AS jsonb),statistics=CAST(? AS jsonb),finished_at=clock_timestamp() WHERE id=?",manifestJson,statistics,id);
            });
            return status(investigation);
        }catch(Exception ex){if(inserted)jdbc.update("UPDATE context_indexes SET state='FAILED',technical_error=?,finished_at=clock_timestamp() WHERE id=?",shortError(ex),id);throw fail("Context indexing failed",ex);}
        finally{building.remove(investigation);}
    }
    private List<ContextDocuments.Document> collect(UUID investigation,Source source,Selection selection) throws Exception {
        var docs=new ArrayList<ContextDocuments.Document>();
        for(String path:new LinkedHashSet<>(selection.documentationPaths()))docs.add(ContextDocuments.snapshot(source.base(),path,source.commit()));
        if(!selection.commitIds().isEmpty()){
            if(!Path.of(source.repo()).toAbsolutePath().normalize().equals(repository))throw new IllegalArgumentException("Snapshot repository differs from the configured history allowlist");
            docs.addAll(new SelectedHistory().read(repository,source.commit(),source.module(),selection.commitIds()));
        }
        for(UUID previous:new LinkedHashSet<>(selection.summaryInvestigationIds())){
            var rows=jdbc.queryForList("SELECT id,title,bug_description,expected_behavior,observed_behavior,workflow_state,outcome,updated_at FROM investigations WHERE id=? AND snapshot_id=? AND created_at<(SELECT created_at FROM investigations WHERE id=?)",previous,source.snapshot(),investigation);
            if(rows.isEmpty())throw new IllegalArgumentException("Previous reports must be older reports for this exact snapshot");
            var report=rows.getFirst();StringBuilder text=new StringBuilder("Historical report inputs and recorded outcome; not an independently verified conclusion.\n");
            for(String field:List.of("id","title","bug_description","expected_behavior","observed_behavior","workflow_state","outcome","updated_at"))text.append(field).append(": ").append(Objects.toString(report.get(field),"not recorded")).append('\n');
            docs.add(new ContextDocuments.Document("PREVIOUS_REPORT","investigation:"+previous,source.commit(),"SAVED_REPORT_LINE",report.get("title").toString(),text.toString()));
        }
        if(selection.includeExecutionEvidence()){
            var jobs=jdbc.queryForList("SELECT j.id,j.result::text AS result,j.stdout,j.stderr FROM execution_jobs j JOIN executions e ON e.id=j.id WHERE e.investigation_id=? AND j.snapshot_id=? AND j.queue_state='COMPLETED' ORDER BY j.created_at DESC,j.id LIMIT 5",investigation,source.snapshot());
            for(var job:jobs){
                String ref="execution:"+job.get("id");
                if(job.get("result")!=null)docs.add(new ContextDocuments.Document("EXECUTION_RESULT",ref+":result",source.commit(),"SAVED_JSON_LINE","Saved execution result "+job.get("id"),mapper.readTree(job.get("result").toString()).toPrettyString()));
                for(String stream:List.of("stdout","stderr"))if(job.get(stream)!=null&&!job.get(stream).toString().isBlank())docs.add(new ContextDocuments.Document("EXECUTION_"+stream.toUpperCase(Locale.ROOT),ref+":"+stream,source.commit(),"LOG_LINE","Saved "+stream+" "+job.get("id"),job.get(stream).toString()));
            }
        }
        return List.copyOf(docs);
    }
    public Reference citation(UUID investigation,UUID index,int chunk){
        source(investigation);
        var rows=jdbc.query("SELECT c.* FROM context_chunks c JOIN context_indexes i ON i.id=c.index_id WHERE c.index_id=? AND c.chunk_number=? AND i.investigation_id=? AND i.state='COMPLETE'",(rs,n)->reference(rs),index,chunk,investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved citation not found for this investigation");
        var citation=rows.getFirst();if(!ContextDocuments.hash(citation.text()).equals(citation.chunkHash()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Saved citation text does not match its content hash");return citation;
    }
    private Reference reference(java.sql.ResultSet rs) throws java.sql.SQLException{return new Reference((UUID)rs.getObject("index_id"),rs.getInt("chunk_number"),rs.getString("origin"),rs.getString("source_ref"),rs.getString("source_revision"),rs.getString("line_kind"),rs.getInt("start_line"),rs.getInt("end_line"),rs.getString("document_hash"),rs.getString("chunk_hash"),rs.getString("title"),rs.getString("source_text"));}
    public Context retrieve(UUID investigation,String query,boolean history,boolean summaries,boolean semantic){
        var primary=current.retrieve(investigation,query,10,semantic,true);var source=source(investigation);var notices=new ArrayList<String>();
        var indexes=jdbc.queryForList("SELECT id,model_digest,statistics::text AS statistics FROM context_indexes WHERE investigation_id=? AND snapshot_id=? AND content_hash=? AND algorithm_version=? AND state='COMPLETE' ORDER BY created_at DESC,id LIMIT 1",investigation,source.snapshot(),source.hash(),ContextDocuments.VERSION);
        if(indexes.isEmpty())return new Context(primary,Map.of(),false,List.of(),List.of("Build the selected documentation/history collection first"));
        var index=indexes.getFirst();UUID indexId=(UUID)index.get("id");
        // These opt-in filters apply to both lexical and vector retrieval before ranking.
        String filter=" AND (? OR c.origin NOT IN ('HISTORICAL_COMMIT','HISTORICAL_DIFF')) AND (? OR c.origin<>'PREVIOUS_REPORT')";
        var exact=jdbc.query("SELECT c.* FROM context_chunks c WHERE c.index_id=?"+filter+" AND strpos(lower(c.source_text||' '||c.title||' '||c.source_ref),lower(?))>0 ORDER BY c.source_ref,c.start_line LIMIT 30",(rs,n)->reference(rs),indexId,history,summaries,query);
        var references=new LinkedHashMap<String,Reference>();var channels=new LinkedHashMap<String,List<HybridRanking.Candidate>>();
        channels.put("EXACT",exact.stream().map(ref->{String key=ref.indexId()+":"+ref.chunkNumber();references.put(key,ref);return candidate(ref,null);}).toList());
        boolean used=false;
        if(semantic)try{
            if(!model.digest().equals(index.get("model_digest")))throw new IllegalStateException("Supplementary model digest changed; rebuild this collection");
            String vector=model.embed(List.of("task: search result | query: "+query),Duration.ofSeconds(30)).getFirst();
            if(!model.digest().equals(index.get("model_digest")))throw new IllegalStateException("Model changed during supplementary retrieval");
            var semanticRows=jdbc.query("SELECT c.*,1-(c.embedding <=> CAST(? AS vector)) AS similarity FROM context_chunks c WHERE c.index_id=?"+filter+" ORDER BY c.embedding <=> CAST(? AS vector),c.chunk_number LIMIT 30",(rs,n)->Map.entry(reference(rs),rs.getDouble("similarity")),vector,indexId,history,summaries,vector);
            channels.put("SEMANTIC",semanticRows.stream().map(entry->{var ref=entry.getKey();references.put(ref.indexId()+":"+ref.chunkNumber(),ref);return candidate(ref,entry.getValue());}).toList());used=true;
        }catch(Exception ex){notices.add("Supplementary semantic retrieval unavailable: "+shortError(ex)+"; exact matches remain available");}
        var matches=HybridRanking.fuse(channels,12).stream().map(rank->new Match(references.get(rank.context().key()),rank.score(),rank.channels(),rank.context().cosineSimilarity())).toList();
        notices.add("History and previous reports are separate evidence sources. Diff/log/saved-report line numbers do not identify current Java source lines.");
        verify(investigation,source);
        return new Context(primary,Map.of("id",indexId,"modelDigest",index.get("model_digest"),"statistics",mapper.readTree(index.get("statistics").toString())),used,matches,List.copyOf(notices));
    }
    private static HybridRanking.Candidate candidate(Reference ref,Double similarity){return new HybridRanking.Candidate(ref.indexId()+":"+ref.chunkNumber(),ref.sourceRef(),ref.startLine(),ref.endLine(),ref.title(),ref.text(),null,similarity);}
    private static String shortError(Exception ex){String text=Objects.toString(ex.getMessage(),ex.getClass().getSimpleName());return text.substring(0,Math.min(text.length(),1000));}
    private static ResponseStatusException fail(String message,Exception ex){if(ex instanceof ResponseStatusException response)return response;return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message+": "+shortError(ex),ex);}
}
