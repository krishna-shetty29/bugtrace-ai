package com.bugtrace.backend;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiProviderTest {
    private final String key="sk-test-abcdefghijklmnop";
    private AiProvider.Request request(){return new AiProvider.Request("account-test-model","Use evidence only","some evidence",1500);}
    @Test void mockFactoryNeverUsesTheCredentialOrClaimsMeasuredUsage() throws Exception {
        var settings=new AiSettings("mock","ignored-live-model",key,60,1500);var provider=new AiProviderConfiguration().aiProvider(settings);
        var response=provider.generate(request());assertEquals("mock-v1",response.model());assertEquals("MOCK_NOT_MEASURED",response.usage().kind());assertNull(response.usage().totalTokens());assertTrue(response.text().startsWith("MOCK RESPONSE"));assertFalse(settings.publicSettings().toString().contains(key));assertFalse(settings.toString().contains(key));assertEquals("[REDACTED]",settings.redact(key));
    }
    @Test void parsesAllMessageTextAndReportedUsageWithoutSendingKeyInBody() throws Exception {
        var body=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var server=server(200,"{\"status\":\"completed\",\"model\":\"returned-model\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"first\"},{\"type\":\"output_text\",\"text\":\"second\"}]}],\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120}}",body,auth,0);
        try{var response=client(server,3).generate(request());assertEquals("first\nsecond",response.text());assertEquals("returned-model",response.model());assertEquals(120L,response.usage().totalTokens());assertEquals("req_test",response.requestId());assertTrue(body.get().contains("\"store\":false"));assertTrue(body.get().contains("\"tools\":[]"));assertFalse(body.get().contains(key));assertEquals("Bearer "+key,auth.get());}finally{server.stop(0);}
    }
    @Test void partialAndRefusedResponsesRetainUsage() throws Exception {
        for(String status:new String[]{"incomplete","completed"}){
            String content=status.equals("incomplete")?"{\"type\":\"output_text\",\"text\":\"partial\"}":"{\"type\":\"refusal\",\"refusal\":\"cannot answer\"}";
            var server=server(200,"{\"status\":\""+status+"\",\"model\":\"m\",\"output\":[{\"type\":\"message\",\"content\":["+content+"]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":1,\"total_tokens\":6}}",new AtomicReference<>(),new AtomicReference<>(),0);
            try{var response=client(server,3).generate(request());assertEquals(6L,response.usage().totalTokens());if(status.equals("incomplete"))assertTrue(response.incomplete());else assertEquals("REFUSED",response.errorCode());}finally{server.stop(0);}
        }
    }
    @Test void providerErrorsDoNotExposeRawBodiesOrCredentials() throws Exception {
        var server=server(401,"{\"error\":\""+key+"\"}",new AtomicReference<>(),new AtomicReference<>(),0);
        try{var error=assertThrows(AiProvider.Failure.class,()->client(server,3).generate(request()));assertEquals("AUTHENTICATION_ERROR",error.code());assertFalse(error.getMessage().contains(key));}finally{server.stop(0);}
    }
    @Test void deadlineProducesAnExplicitUncertainCompletionError() throws Exception {
        var server=server(200,"{}",new AtomicReference<>(),new AtomicReference<>(),1500);
        try{assertEquals("TIMED_OUT",assertThrows(AiProvider.Failure.class,()->client(server,1).generate(request())).code());}finally{server.stop(0);}
    }
    private OpenAiResponsesProvider client(HttpServer server,int timeout){return new OpenAiResponsesProvider(key,timeout,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"));}
    private HttpServer server(int status,String body,AtomicReference<String> request,AtomicReference<String> authorization,int delay) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/responses",exchange->{
            try{request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException ex){Thread.currentThread().interrupt();}byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("x-request-id","req_test");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}catch(java.io.IOException ignored){exchange.close();}
        });server.start();return server;
    }
}
