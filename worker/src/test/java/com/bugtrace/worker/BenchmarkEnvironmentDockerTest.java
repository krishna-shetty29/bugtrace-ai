package com.bugtrace.worker;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_ENVIRONMENT_DOCKER_TEST",matches="true")
class BenchmarkEnvironmentDockerTest {
    @TempDir Path temp;
    private final JsonMapper json=JsonMapper.builder().build();
    private record Output(int code,String text) {}
    private Output command(List<String> args) throws Exception {
        Path log=temp.resolve(UUID.randomUUID()+".log");
        Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if(!p.waitFor(30,TimeUnit.SECONDS))throw new IllegalStateException("Docker environment check timed out");
            if(Files.size(log)>262144)throw new IllegalStateException("Docker environment output exceeds limit");
            return new Output(p.exitValue(),Files.readString(log));
        } finally {p.destroyForcibly();}
    }
    private Map<?,?> inspect(String name) throws Exception {
        var output=command(List.of("docker","inspect",name));assertEquals(0,output.code(),output.text());
        var array=json.readTree(output.text());assertEquals(1,array.size());return json.convertValue(array.get(0),Map.class);
    }
    @Test void verifiesRealRestrictedImageAndJavaWithoutRepositoryExecution() throws Exception {
        String probe="bugtrace-"+UUID.randomUUID(),maven="bugtrace-"+UUID.randomUUID();
        Path source=Files.createDirectory(temp.resolve("source")),cache=Files.createDirectory(temp.resolve("cache"));
        Path evidence=Path.of(System.getProperty("user.home"),"benchmark-measurements","environment-checks",UUID.randomUUID().toString());
        Files.createDirectories(evidence);System.out.println("Environment evidence: "+evidence);
        var containers=new ArrayList<String>();
        var report=new LinkedHashMap<String,Object>();report.put("createdAt",Instant.now().toString());
        report.put("pinnedImage",BenchmarkWorkerRegistration.IMAGE);report.put("repositoryCodeExecuted",false);report.put("passed",false);
        try {
            var created=command(DockerCommands.environmentProbe(probe,BenchmarkWorkerRegistration.IMAGE));
            assertEquals(0,created.code(),created.text());containers.add(probe);
            var probeInspect=inspect(probe);BenchmarkEnvironmentPolicy.validate(probeInspect,source,cache,true);
            report.put("probeObserved",BenchmarkEnvironmentPolicy.facts(probeInspect));
            report.put("probeEnvironmentValidated",true);report.put("resolvedImageId",probeInspect.get("Image"));
            var run=command(List.of("docker","start","--attach",probe));
            Files.writeString(evidence.resolve("java-probe.log"),run.text());assertEquals(0,run.code(),run.text());
            var finished=inspect(probe);var state=(Map<?,?>)finished.get("State");
            assertEquals(false,state.get("Running"));assertEquals(0,((Number)state.get("ExitCode")).intValue());assertEquals(false,state.get("OOMKilled"));
            BenchmarkEnvironmentPolicy.requireJava21(run.text());report.put("containerJava21Verified",true);
            var build=command(DockerCommands.create(maven,BenchmarkWorkerRegistration.IMAGE,source,cache,List.of("test-compile")));
            assertEquals(0,build.code(),build.text());containers.add(maven);var mavenInspect=inspect(maven);
            report.put("mavenObserved",BenchmarkEnvironmentPolicy.facts(mavenInspect));
            report.put("expectedExperimentPath",source.toAbsolutePath().normalize().toString());
            report.put("expectedCachePath",cache.toAbsolutePath().normalize().toString());
            BenchmarkEnvironmentPolicy.validate(mavenInspect,source,cache,false);
            report.put("mavenEnvironmentValidated",true);report.put("passed",true);
        } finally {
            var removal=new ArrayList<>(List.of("docker","rm","--force"));removal.addAll(containers);
            var cleanup=containers.isEmpty()?new Output(0,""):command(removal);
            report.put("cleanupExitCode",cleanup.code());
            if(cleanup.code()!=0)report.put("passed",false);
            report.put("finishedAt",Instant.now().toString());
            Files.writeString(evidence.resolve("environment-check.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            assertEquals(0,cleanup.code(),"Container cleanup failed: "+cleanup.text());
        }
    }
}
