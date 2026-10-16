package com.bugtrace.backend;

import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hash the entire embedding input, including title, to prevent incorrect reuse. */
public final class IncrementalEmbeddings {
    public record Result(List<String> vectors,int reused,int generated){}
    private final JdbcTemplate jdbc;private final OllamaEmbeddings model;
    public IncrementalEmbeddings(JdbcTemplate jdbc,OllamaEmbeddings model){this.jdbc=jdbc;this.model=model;}
    public Result embed(List<String> inputs,String digest,long deadline) throws Exception {
        var unique=new LinkedHashMap<String,String>();inputs.forEach(input->unique.putIfAbsent(ContextDocuments.hash(input),input));
        var values=new HashMap<String,String>();int generated=0;
        for(var entry:unique.entrySet()){
            var cached=jdbc.queryForList("SELECT embedding::text AS vector FROM context_embedding_cache WHERE model_digest=? AND input_hash=?",digest,entry.getKey());
            if(!cached.isEmpty())values.put(entry.getKey(),cached.getFirst().get("vector").toString());
        }
        var missing=unique.keySet().stream().filter(hash->!values.containsKey(hash)).toList();
        for(int offset=0;offset<missing.size();offset+=4){
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("Context embedding deadline exceeded");
            if(!digest.equals(model.digest()))throw new IllegalStateException("Model changed during incremental indexing");
            var batch=missing.subList(offset,Math.min(offset+4,missing.size()));
            var vectors=model.embed(batch.stream().map(unique::get).toList(),Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(60).toNanos())));
            if(!digest.equals(model.digest()))throw new IllegalStateException("Model changed during incremental indexing");
            for(int n=0;n<batch.size();n++){
                jdbc.update("INSERT INTO context_embedding_cache(model_digest,input_hash,embedding) VALUES (?,?,CAST(? AS vector)) ON CONFLICT DO NOTHING",digest,batch.get(n),vectors.get(n));
                values.put(batch.get(n),vectors.get(n));generated++;
            }
        }
        return new Result(inputs.stream().map(input->values.get(ContextDocuments.hash(input))).toList(),inputs.size()-generated,generated);
    }
}
