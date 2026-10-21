package com.bugtrace.worker;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.bugtrace.worker.WorkerController.*;

class BenchmarkWorkerRegistrationTest {
    @TempDir Path temp;
    final UUID registration=UUID.randomUUID(), investigation=UUID.randomUUID(), snapshot=UUID.randomUUID();
    final String hash="a".repeat(64);
    Map<String,Object> env() {
        var m=new HashMap<String,Object>();
        m.put("javaMajor",21);m.put("buildTool","MAVEN");m.put("containerImage",BenchmarkWorkerRegistration.IMAGE);
        m.put("network","none");m.put("containerUser","1000:1000");m.put("cpus",1);m.put("memoryBytes",1073741824);
        m.put("pidsLimit",128);m.put("jobTimeoutSeconds",120);m.put("trustedFixtureOnly",true);
        m.put("credentialsAllowed",false);m.put("dockerSocketMountAllowed",false);
        m.put("dependencyPreparation","Use an already prepared artifact-only cache copy inside the restricted container. No host settings.xml.");return m;
    }
    Map<String,Object> document() {
        var m=new HashMap<String,Object>();m.put("caseId","case-001");m.put("modulePath","cases/case-001");
        m.put("releaseCommit","6c4f8856bb7e4d49e953a8b086d594d2f505838d");m.put("snapshotContentHash",hash);
        m.put("generatedTestPackage","com.bugtrace.benchmark");m.put("generatedTestClass",BenchmarkWorkerRegistration.TEST_CLASS);
        m.put("generatedTestPath",BenchmarkWorkerRegistration.TEST_PATH);m.put("environment",env());return m;
    }
    Map<String,Object> captured() {
        var m=new HashMap<String,Object>();m.put("verified",true);m.put("registrationId",registration.toString());
        m.put("investigationId",investigation.toString());m.put("snapshotId",snapshot.toString());m.put("contentHash",hash);
        m.put("basePath",temp.toString());m.put("caseId","case-001");m.put("modulePath","cases/case-001");
        m.put("releaseCommit","6c4f8856bb7e4d49e953a8b086d594d2f505838d");m.put("benchmarkContentHash",hash);return m;
    }
    BenchmarkWorkerRegistration validate(Map<String,Object> doc,Map<String,Object> capture) {
        return BenchmarkWorkerRegistration.validate(Map.of("verified",true,"registrationId",registration.toString(),"registration",doc),capture,registration,investigation,snapshot,hash,temp,BenchmarkWorkerRegistration.IMAGE);
    }
    GeneratedTestOverlay.Payload payload() {
        String source="package com.bugtrace.benchmark; class GeneratedBenchmarkReproductionTest {}";
        return new GeneratedTestOverlay.Payload(BenchmarkWorkerRegistration.TEST_PATH,BenchmarkWorkerRegistration.TEST_CLASS,source,GeneratedTestOverlay.sha(source),"intended failure");
    }
    JobRequest job(UUID snapshot,int timeout,GeneratedTestOverlay.Payload payload) {
        return new JobRequest(UUID.randomUUID(),snapshot,Operation.RUN_SELECTED_TEST,BenchmarkWorkerRegistration.TEST_CLASS,timeout,payload);
    }
    @Test void acceptsOnlyBoundSnapshotAndApprovedContract(){var c=validate(document(),captured());assertDoesNotThrow(()->c.validateJob(job(snapshot,120,payload())));}
    @Test void rejectsChangedBindingFields(){for(String key:List.of("registrationId","investigationId","snapshotId","contentHash","caseId","modulePath","releaseCommit","benchmarkContentHash")){var m=captured();m.put(key,"different");assertThrows(Exception.class,()->validate(document(),m),key);}}
    @Test void backendFilesystemPathIsNotAWorkerBinding(){var m=captured();m.put("basePath","/server/only/base");assertNotNull(validate(document(),m));}
    @Test void rejectsUnverifiedSnapshot(){var m=captured();m.put("verified",false);assertThrows(Exception.class,()->validate(document(),m));}
    @Test void rejectsChangedClassPathAndRelease(){for(String key:List.of("generatedTestClass","generatedTestPath","generatedTestPackage","caseId","modulePath","releaseCommit")){var m=document();m.put(key,"arbitrary");assertThrows(Exception.class,()->validate(m,captured()),key);}}
    @Test void rejectsEveryChangedEnvironmentField(){for(String key:env().keySet()){var e=env();e.put(key,"changed");var m=document();m.put("environment",e);assertThrows(Exception.class,()->validate(m,captured()),key);}}
    @Test void rejectsAddedEnvironmentPermissions(){var e=env();e.put("dockerFlags","--privileged");var m=document();m.put("environment",e);assertThrows(Exception.class,()->validate(m,captured()));}
    @Test void rejectsForeignSnapshotAndLongTimeout(){var c=validate(document(),captured());assertThrows(Exception.class,()->c.validateJob(job(UUID.randomUUID(),120,payload())));assertThrows(Exception.class,()->c.validateJob(job(snapshot,121,payload())));}
    @Test void rejectsMissingOverlayAndSuiteExecution(){var c=validate(document(),captured());assertThrows(Exception.class,()->c.validateJob(job(snapshot,120,null)));assertThrows(Exception.class,()->c.validateJob(new JobRequest(UUID.randomUUID(),snapshot,Operation.RUN_TEST_SUITE,null,120)));}
    @Test void rejectsTamperedOrOversizedSource(){var c=validate(document(),captured());var p=payload();assertThrows(Exception.class,()->c.validateJob(job(snapshot,120,new GeneratedTestOverlay.Payload(p.path(),p.className(),p.source()+"changed",p.sourceSha256(),"failure"))));String big="x".repeat(12001);assertThrows(Exception.class,()->c.validateJob(job(snapshot,120,new GeneratedTestOverlay.Payload(p.path(),p.className(),big,GeneratedTestOverlay.sha(big),"failure"))));}
    @Test void checkoutDefaultStillRejectsBenchmarkPayload(){assertThrows(Exception.class,()->GeneratedTestOverlay.validate(payload()));}
    @Test void installUsesFreshCopyAndCannotOverwrite() throws Exception {
        var c=validate(document(),captured());Path base=temp.resolve("base"),experiment=temp.resolve("experiment");Files.createDirectory(base);Files.writeString(base.resolve("pom.xml"),"<project/>");String before=SnapshotFiles.hash(base);SnapshotFiles.copy(base,experiment);
        assertEquals(payload().sourceSha256(),GeneratedTestOverlay.install(experiment,payload(),c.testPath(),c.testClass()));
        assertEquals(before,SnapshotFiles.hash(base));assertFalse(Files.exists(base.resolve(c.testPath())));
        assertThrows(FileAlreadyExistsException.class,()->GeneratedTestOverlay.install(experiment,payload(),c.testPath(),c.testClass()));
    }
    @Test void arbitraryApprovedPairCannotBroadenOverlayScope(){var p=payload();assertThrows(Exception.class,()->GeneratedTestOverlay.validate(p,"../Test.java","Arbitrary"));}
    @Test void rejectsJobPayloadOutsideApprovedGeneratedLocation() {
        var c=validate(document(),captured());var p=payload();
        for(String path:List.of("../Outside.java","src/test/java/OtherTest.java","C:/Users/ADMIN/bbh/Outside.java")) {
            var bad=new GeneratedTestOverlay.Payload(path,p.className(),p.source(),p.sourceSha256(),p.expectedFailureSignature());
            assertThrows(Exception.class,()->c.validateJob(job(snapshot,120,bad)),path);
        }
    }
}
