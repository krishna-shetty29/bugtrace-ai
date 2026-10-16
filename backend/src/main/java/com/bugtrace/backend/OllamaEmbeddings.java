package com.bugtrace.backend;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OllamaEmbeddings {
    public static final int DIMENSIONS=768;
    private final URI base;
    private final String model;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OllamaEmbeddings(@Value("${bugtrace.embeddings.url:http://127.0.0.1:11434}") String url,
        @Value("${bugtrace.embeddings.model:embeddinggemma:latest}") String model){
        base=URI.create(url);
        if(!"http".equals(base.getScheme())||!Set.of("127.0.0.1","localhost","[::1]","::1").contains(base.getHost())||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/")))
            throw new IllegalArgumentException("Embedding endpoint must be a local HTTP origin");
        if(!model.equals("embeddinggemma:latest"))throw new IllegalArgumentException("This index version supports embeddinggemma:latest only");
        this.model=model;
    }
    public String model(){return model;}
    public String digest() throws Exception {
        var root=request("/api/tags",null,Duration.ofSeconds(5));
        for(var item:root.path("models"))if(model.equals(item.path("name").asText())||model.equals(item.path("model").asText())){
            String digest=item.path("digest").asText();
            if(!digest.matches("(?:sha256:)?[a-fA-F0-9]{64}"))throw new IllegalStateException("Model digest is missing or invalid");
            return digest;
        }
        throw new IllegalStateException("Pull embeddinggemma in the local Ollama service first");
    }
    public List<String> embed(List<String> input,Duration timeout) throws Exception {
        if(input.isEmpty()||input.size()>8)throw new IllegalArgumentException("Embedding batch must contain 1 to 8 inputs");
        var root=request("/api/embed",Map.of("model",model,"input",input,"truncate",false,"dimensions",DIMENSIONS,"keep_alive","5m"),timeout);
        var vectors=root.path("embeddings");
        if(!vectors.isArray()||vectors.size()!=input.size())throw new IllegalStateException("Wrong embedding count");
        var result=new ArrayList<String>();
        for(var vector:vectors){
            if(!vector.isArray()||vector.size()!=DIMENSIONS)throw new IllegalStateException("Expected 768 embedding dimensions");
            double norm=0;StringJoiner literal=new StringJoiner(",","[","]");
            for(var number:vector){
                double value=number.asDouble();
                if(!number.isNumber()||!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw new IllegalStateException("Invalid embedding value");
                norm+=value*value;literal.add(Float.toString((float)value));
            }
            if(norm<1e-20||!Double.isFinite(norm))throw new IllegalStateException("Zero or invalid embedding norm");
            result.add(literal.toString());
        }
        return List.copyOf(result);
    }
    private tools.jackson.databind.JsonNode request(String path,Object body,Duration timeout) throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Accept","application/json");
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        {
            byte[] bytes=response.body();
            if(bytes.length>2*1024*1024)throw new IllegalStateException("Embedding response exceeds 2 MiB");
            if(response.statusCode()!=200)throw new IllegalStateException("Local embedding service HTTP "+response.statusCode()+"; check model, memory and context size");
            return mapper.readTree(bytes);
        }
    }
}
