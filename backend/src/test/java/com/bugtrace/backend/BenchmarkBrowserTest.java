package com.bugtrace.backend;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkBrowserTest {
    BenchmarkBrowserService service(){return new BenchmarkBrowserService(null,null,null,null,null);}
    @Test void rejectsMissingRequestBeforeRepositoryAccess(){assertThrows(ResponseStatusException.class,()->service().create(null));}
    @Test void rejectsMissingUuidBeforeRepositoryAccess(){assertThrows(ResponseStatusException.class,()->service().create(new BenchmarkBrowserService.Create(null,"case-001")));}
    @Test void rejectsUnknownCaseBeforeRepositoryAccess(){assertThrows(ResponseStatusException.class,()->service().create(new BenchmarkBrowserService.Create(UUID.randomUUID(),"case-007")));}
    @Test void rejectsPrivatePathsBeforeRepositoryAccess(){for(String path:List.of("bbh","bbv","../bbh","C:/Users/ADMIN/bbv"))assertThrows(ResponseStatusException.class,()->service().create(new BenchmarkBrowserService.Create(UUID.randomUUID(),path)));}
    @Test void rejectsModulePathsAndCommands(){for(String value:List.of("cases/case-001","case-001; whoami","http://localhost/private"))assertThrows(ResponseStatusException.class,()->service().create(new BenchmarkBrowserService.Create(UUID.randomUUID(),value)));}
}
