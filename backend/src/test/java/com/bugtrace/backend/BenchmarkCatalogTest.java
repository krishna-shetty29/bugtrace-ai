package com.bugtrace.backend;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCatalogTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private String environment(){return """
        {"javaMajor":21,"buildTool":"MAVEN","containerImage":"%s","network":"none","containerUser":"1000:1000","cpus":1,"memoryBytes":1073741824,"pidsLimit":128,"jobTimeoutSeconds":120,"trustedFixtureOnly":true,"credentialsAllowed":false,"dockerSocketMountAllowed":false,"dependencyPreparation":"Use an already prepared artifact-only cache copy inside the restricted container. No host settings.xml."}
        """.formatted(BenchmarkCatalog.IMAGE);}
    @Test void acceptsOnlyNormalizedRelativePaths(){assertEquals("cases/case-001",BenchmarkCatalog.relative("cases/case-001"));for(String path:new String[]{"../bbh","a/../b","/tmp/x","C:/Users/x","a\\b","a//b","a/","./a","a./b"})assertThrows(IllegalArgumentException.class,()->BenchmarkCatalog.relative(path),path);}
    @Test void requiresFullPinnedCommit(){assertThrows(IllegalArgumentException.class,()->new BenchmarkCatalog(Path.of("."),"main",BenchmarkCatalog.MANIFEST));}
    @Test void rejectsUnpinnedManifest(){assertThrows(IllegalArgumentException.class,()->new BenchmarkCatalog(Path.of("."),"a".repeat(40),"latest"));}
    @Test void approvedEnvironmentPasses(){assertDoesNotThrow(()->BenchmarkCatalog.environment(json.readTree(environment())));}
    @Test void rejectsNetworkAccess(){assertThrows(IllegalArgumentException.class,()->BenchmarkCatalog.environment(json.readTree(environment().replace("\"none\"","\"bridge\""))));}
    @Test void rejectsMutableImage(){assertThrows(IllegalArgumentException.class,()->BenchmarkCatalog.environment(json.readTree(environment().replace(BenchmarkCatalog.IMAGE,"maven:latest"))));}
    @Test void rejectsCredentials(){assertThrows(IllegalArgumentException.class,()->BenchmarkCatalog.environment(json.readTree(environment().replace("\"credentialsAllowed\":false","\"credentialsAllowed\":true"))));}
    @Test void rejectsLargerDeadline(){assertThrows(IllegalArgumentException.class,()->BenchmarkCatalog.environment(json.readTree(environment().replace("\"jobTimeoutSeconds\":120","\"jobTimeoutSeconds\":600"))));}
    @Test void rejectsUnknownEnvironmentFields(){assertThrows(IllegalArgumentException.class,()->BenchmarkCatalog.environment(json.readTree(environment().replace("{","{\"extra\":true,"))));}
    @Test void stableRegistrationHashIgnoresMapOrder(){assertEquals(DiagnosisIntegrity.hash(json.readTree("{\"files\":{\"b\":2,\"a\":1}}")),DiagnosisIntegrity.hash(json.readTree("{\"files\":{\"a\":1,\"b\":2}}")));}
}
