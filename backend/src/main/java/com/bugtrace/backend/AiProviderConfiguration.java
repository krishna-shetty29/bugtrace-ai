package com.bugtrace.backend;

import org.springframework.context.annotation.*;

@Configuration
public class AiProviderConfiguration {
    @Bean public AiProvider aiProvider(AiSettings settings){
        return settings.provider().equals("mock")?new MockAiProvider():new OpenAiResponsesProvider(settings.credential(),settings.timeout());
    }
}
