package com.bugtrace.backend;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingsTest {
    @Test void validatesDigestBatchDimensionsNormAndNonTruncatingRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var response=new AtomicReference<>("{\"embeddings\":[["+"0.1,".repeat(767)+"0.1]]}");
        var request=new AtomicReference<String>();
        server.createContext("/api/tags",exchange->{byte[] bytes=("{\"models\":[{\"name\":\"embeddinggemma:latest\",\"digest\":\""+"a".repeat(64)+"\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}});
        server.createContext("/api/embed",exchange->{request.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] bytes=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}});
        server.start();
        try{
            var client=new OllamaEmbeddings("http://127.0.0.1:"+server.getAddress().getPort(),"embeddinggemma:latest");
            assertEquals("a".repeat(64),client.digest());
            assertEquals(1,client.embed(List.of("query"),Duration.ofSeconds(3)).size());
            assertTrue(request.get().contains("\"truncate\":false"));assertTrue(request.get().contains("\"dimensions\":768"));
            response.set("{\"embeddings\":[[0.1,0.2]]}");assertThrows(IllegalStateException.class,()->client.embed(List.of("query"),Duration.ofSeconds(3)));
            response.set("{\"embeddings\":[["+"0,".repeat(767)+"0]]}");assertThrows(IllegalStateException.class,()->client.embed(List.of("query"),Duration.ofSeconds(3)));
            response.set("{\"embeddings\":[]}");assertThrows(IllegalStateException.class,()->client.embed(List.of("query"),Duration.ofSeconds(3)));
        }finally{server.stop(0);}
    }
    @Test void rejectsRemoteOrMisconfiguredEndpoints(){
        assertThrows(IllegalArgumentException.class,()->new OllamaEmbeddings("https://example.com","embeddinggemma:latest"));
        assertThrows(IllegalArgumentException.class,()->new OllamaEmbeddings("http://127.0.0.1:11434/api","embeddinggemma:latest"));
        assertThrows(IllegalArgumentException.class,()->new OllamaEmbeddings("http://127.0.0.1:11434","different-model"));
    }
}
