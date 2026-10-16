package com.bugtrace.backend;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Live adapter. Default application mode is mock; no automatic request retry. */
public final class OpenAiResponsesProvider implements AiProvider {
    private final String key;private final int timeout;private final URI endpoint;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OpenAiResponsesProvider(String key,int timeout){this(key,timeout,URI.create("https://api.openai.com/v1/responses"));}
    // Local fake HTTP server seam; the production endpoint is not frontend-configurable.
    OpenAiResponsesProvider(String key,int timeout,URI endpoint){this.key=key;this.timeout=timeout;this.endpoint=endpoint;}
    @Override public Response generate(Request request) throws Failure {
        if(key==null||key.isBlank()||request.model().equals("mock-v1"))throw new Failure("CONFIGURATION_ERROR","Set backend OPENAI_API_KEY and BUGTRACE_AI_MODEL before using the live provider",null);
        try{
            String body=mapper.writeValueAsString(Map.of("model",request.model(),"instructions",request.instructions(),"input",request.input(),"max_output_tokens",request.maxOutputTokens(),"store",false,"tools",List.of()));
            var call=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeout)).header("Authorization","Bearer "+key).header("Content-Type","application/json").header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(call,HttpResponse.BodyHandlers.ofByteArray());String requestId=response.headers().firstValue("x-request-id").orElse(null);
            if(response.statusCode()!=200){
                String code=switch(response.statusCode()){case 401,403->"AUTHENTICATION_ERROR";case 429->"RATE_LIMIT_OR_QUOTA";case 400,404,422->"REQUEST_OR_MODEL_ERROR";default->"PROVIDER_HTTP_ERROR";};
                throw new Failure(code,"Provider returned HTTP "+response.statusCode()+". Check backend model/access/quota settings; this call was not retried.",safeId(requestId));
            }
            if(response.body().length>1024*1024)throw new Failure("RESPONSE_TOO_LARGE","Provider response exceeded 1 MiB",safeId(requestId));
            JsonNode root=mapper.readTree(response.body());String state=root.path("status").asText();
            StringBuilder text=new StringBuilder();boolean refusal=false;
            for(var output:root.path("output"))if("message".equals(output.path("type").asText()))for(var item:output.path("content")){
                if("output_text".equals(item.path("type").asText())){if(!text.isEmpty())text.append('\n');text.append(item.path("text").asText());}
                if("refusal".equals(item.path("type").asText()))refusal=true;
            }
            if(text.length()>32000)throw new Failure("RESPONSE_TOO_LARGE","Model text exceeded 32000 characters",safeId(requestId));
            var usage=root.path("usage");Long input=number(usage,"input_tokens"),output=number(usage,"output_tokens"),total=number(usage,"total_tokens");
            String error=!Set.of("completed","incomplete").contains(state)?"PROVIDER_RESPONSE_FAILED":refusal?"REFUSED":text.isEmpty()&&!state.equals("incomplete")?"EMPTY_RESPONSE":null;
            return new Response(root.path("model").asText(request.model()),safeId(requestId),text.toString(),new Usage(input,output,total,input!=null||output!=null||total!=null?"MEASURED":"UNAVAILABLE"),state.equals("incomplete"),error);
        }catch(Failure ex){throw ex;}
        catch(java.net.http.HttpTimeoutException ex){throw new Failure("TIMED_OUT","Provider request timed out. Completion and billing status may be unknown; no automatic retry was sent.",null);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new Failure("INTERRUPTED","Provider request was interrupted; completion may be unknown",null);}
        catch(Exception ex){throw new Failure("PROVIDER_TRANSPORT_OR_FORMAT","Provider connection or response parsing failed; no automatic retry was sent",null);}
    }
    private static String safeId(String id){return id!=null&&id.matches("[A-Za-z0-9_.:-]{1,200}")?id:null;}
    private static Long number(JsonNode node,String field){var value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)return null;return value.asLong();}
}
