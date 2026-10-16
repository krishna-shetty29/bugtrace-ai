package com.bugtrace.backend;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AiSettings {
    private final String provider,model,key;
    private final int timeout,maxOutput;
    public AiSettings(@Value("${bugtrace.ai.provider:mock}") String provider,
        @Value("${bugtrace.ai.model:mock-v1}") String model,@Value("${bugtrace.ai.api-key:}") String key,
        @Value("${bugtrace.ai.timeout-seconds:60}") int timeout,@Value("${bugtrace.ai.max-output-tokens:1500}") int maxOutput){
        if(!Set.of("mock","openai").contains(provider))throw new IllegalArgumentException("AI provider must be mock or openai");
        if(timeout<1||timeout>120||maxOutput<128||maxOutput>4000)throw new IllegalArgumentException("Invalid AI timeout/output bounds");
        if(model==null||!model.matches("[A-Za-z0-9._:/-]{1,150}"))throw new IllegalArgumentException("Invalid configured AI model identifier");
        this.provider=provider;this.model=provider.equals("mock")?"mock-v1":model;this.key=key;this.timeout=timeout;this.maxOutput=maxOutput;
    }
    public String provider(){return provider;} public String model(){return model;}
    public int timeout(){return timeout;}public int maxOutput(){return maxOutput;}
    // Credential is never included in public settings, records, toString, or prompts.
    String credential(){return key;}
    public String redact(String text){
        if(text==null)return null;
        if(key!=null&&!key.isBlank())text=text.replace(key,"[REDACTED]");
        return text.replaceAll("sk-[A-Za-z0-9_-]{12,}","[REDACTED]");
    }
    public Map<String,Object> publicSettings(){return Map.of("provider",provider,"model",model,"mock",provider.equals("mock"),"promptVersion",AiCallService.PROMPT_VERSION,"timeoutSeconds",timeout,"maxOutputTokens",maxOutput);}
    @Override public String toString(){return "AiSettings[provider="+provider+", credential=REDACTED]";}
}
