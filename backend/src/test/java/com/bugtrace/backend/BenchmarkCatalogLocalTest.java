package com.bugtrace.backend;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_CATALOG_LOCAL_TEST",matches="true")
class BenchmarkCatalogLocalTest {
    @TempDir Path temporary;
    private Path root(){String override=System.getenv("BENCHMARK_TEST_REPOSITORY");return override==null?Path.of(System.getProperty("user.home"),"bbp"):Path.of(override);}
    @Test void validatesActualPinnedSixCaseReleaseReadOnly()throws Exception {
        var catalog=new BenchmarkCatalog(root(),"6c4f8856bb7e4d49e953a8b086d594d2f505838d",BenchmarkCatalog.MANIFEST);
        var cases=catalog.validate();assertEquals(6,cases.size());
        for(var c:cases){assertEquals("cases/"+c.caseId(),c.modulePath());assertEquals("com.bugtrace.benchmark",c.generatedTestPackage());assertEquals(4,c.files().size());assertEquals(1,c.allowedProductionPaths().size());}
    }
    @Test void rejectsDifferentPinnedCommit(){var catalog=new BenchmarkCatalog(root(),"0".repeat(40),BenchmarkCatalog.MANIFEST);assertThrows(IllegalArgumentException.class,catalog::validate);}
    @Test void rejectsDifferentManifestHash(){var catalog=new BenchmarkCatalog(root(),"6c4f8856bb7e4d49e953a8b086d594d2f505838d","0".repeat(64));assertThrows(IllegalArgumentException.class,catalog::validate);}
    private Path copy()throws Exception {
        Path target=temporary.resolve("public-copy");Files.createDirectories(target);
        try(var walk=Files.walk(root())) {for(Path path:walk.toList()) {Path destination=target.resolve(root().relativize(path));if(Files.isDirectory(path))Files.createDirectories(destination);else Files.copy(path,destination);}}
        return target.toRealPath();
    }
    @Test void rejectsModifiedCaseSourceWithoutChangingRelease()throws Exception {
        Path target=copy();Files.writeString(target.resolve("cases/case-001/src/main/java/com/bugtrace/benchmark/CheckoutProcessor.java"),"changed");
        var catalog=new BenchmarkCatalog(target,"6c4f8856bb7e4d49e953a8b086d594d2f505838d",BenchmarkCatalog.MANIFEST);
        var failure=assertThrows(IllegalArgumentException.class,catalog::validate);assertTrue(failure.getMessage().contains("Source bytes changed"));
    }
    @Test void rejectsPrivateOrGeneratedExtraFile()throws Exception {
        Path target=copy();Files.writeString(target.resolve("cases/case-001/hidden-check.txt"),"must stay outside public input");
        var catalog=new BenchmarkCatalog(target,"6c4f8856bb7e4d49e953a8b086d594d2f505838d",BenchmarkCatalog.MANIFEST);
        var failure=assertThrows(IllegalArgumentException.class,catalog::validate);assertTrue(failure.getMessage().contains("Unexpected or missing public files"));
    }
}
