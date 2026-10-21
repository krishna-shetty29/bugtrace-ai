package com.bugtrace.worker;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkerAccessFilterTest {
    @Test void directExecutionAndResultEndpointsCannotBypassBackendQueue()throws Exception {
        var filter=new WorkerAccessFilter();for(String method:new String[]{"GET","POST","DELETE"}){var request=new MockHttpServletRequest(method,"/api/jobs/123");request.setRequestURI("/api/jobs/123");request.addHeader("Authorization","Bearer "+"a".repeat(43));var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->fail("Direct worker access"));assertEquals(403,response.getStatus());}
    }
}
