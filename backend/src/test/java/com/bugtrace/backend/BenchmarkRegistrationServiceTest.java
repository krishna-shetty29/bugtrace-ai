package com.bugtrace.backend;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRegistrationServiceTest {
    private BenchmarkRegistrationService service(boolean enabled){return new BenchmarkRegistrationService(null,enabled,System.getProperty("user.home")+"/bbp","6c4f8856bb7e4d49e953a8b086d594d2f505838d");}
    @Test void rejectsPathsBeforeReadingRepositoryOrDatabase(){for(String path:java.util.List.of("bbh","bbv","../bbh","../bbv","C:\\Users\\ADMIN\\bbh","C:\\Users\\ADMIN\\bbv")){var failure=assertThrows(ResponseStatusException.class,()->service(true).register(new BenchmarkRegistrationService.Create(path)));assertEquals(400,failure.getStatusCode().value(),path);}}
    @Test void rejectsUnknownCaseBeforeDatabaseAccess(){var failure=assertThrows(ResponseStatusException.class,()->service(true).register(new BenchmarkRegistrationService.Create("case-007")));assertEquals(400,failure.getStatusCode().value());}
    @Test void disabledRegistrationDoesNotReadRepositoryOrDatabase(){var failure=assertThrows(ResponseStatusException.class,()->service(false).register(new BenchmarkRegistrationService.Create("case-001")));assertEquals(409,failure.getStatusCode().value());}
}
