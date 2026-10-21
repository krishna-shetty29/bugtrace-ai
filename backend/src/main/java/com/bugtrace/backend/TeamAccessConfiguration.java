package com.bugtrace.backend;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class TeamAccessConfiguration implements WebMvcConfigurer {
    private final TeamPermissionInterceptor permissions;
    public TeamAccessConfiguration(TeamPermissionInterceptor permissions){this.permissions=permissions;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(permissions).addPathPatterns("/api/**");}
}
