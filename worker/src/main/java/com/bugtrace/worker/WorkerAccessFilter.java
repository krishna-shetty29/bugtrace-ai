package com.bugtrace.worker;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Executions enter through the authenticated, budgeted backend queue only. */
@Component
public class WorkerAccessFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/api/jobs")) {
            response.setStatus(403);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");
            response.getWriter().write("{\"code\":\"QUEUE_REQUIRED\",\"message\":\"Direct worker HTTP jobs are disabled; use the authenticated backend queue\"}");return;
        }
        chain.doFilter(request,response);
    }
}
