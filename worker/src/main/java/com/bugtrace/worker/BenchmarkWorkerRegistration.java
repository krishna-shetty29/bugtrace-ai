package com.bugtrace.worker;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Operator-selected registration, fetched from the trusted local backend, never from a job. */
public record BenchmarkWorkerRegistration(UUID registrationId, UUID investigationId, UUID snapshotId,
    String contentHash, String caseId, String testPath, String testClass, Map<String,Object> environment) {
    static final String IMAGE="maven@sha256:5496bf5b90ea4ad5af01d63be191f946f15ef5c375a6dd48fc21482a43108ca5";
    static final String TEST_CLASS="com.bugtrace.benchmark.GeneratedBenchmarkReproductionTest";
    static final String TEST_PATH="src/test/java/com/bugtrace/benchmark/GeneratedBenchmarkReproductionTest.java";
    public BenchmarkWorkerRegistration { environment=Map.copyOf(environment); }

    public static BenchmarkWorkerRegistration fetch(String backend,String registration,String investigation,
        String snapshot,String hash,Path base,String image) throws Exception {
        URI uri=URI.create(backend);
        if(!"http".equals(uri.getScheme())||!Set.of("localhost","127.0.0.1").contains(uri.getHost())
            ||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
            ||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException("Use a localhost backend origin");
        UUID r=UUID.fromString(registration),i=UUID.fromString(investigation);
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String origin=backend.replaceAll("/$","");
        var registered=post(client,origin+"/api/benchmark-registrations/"+r+"/verify");
        var captured=post(client,origin+"/api/benchmark-registrations/"+r+"/snapshots/"+i+"/verify");
        return validate(registered,captured,r,i,UUID.fromString(snapshot),hash,base,image);
    }
    private static Map<String,Object> post(HttpClient client,String url) throws Exception {
        String token=System.getenv("WORKER_ACCESS_TOKEN");if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Snapshot-bound worker access token required");
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            byte[] bytes=stream.readNBytes(262145);
            if(response.statusCode()!=200||bytes.length>262144)throw new IllegalArgumentException("Backend registration verification failed: HTTP "+response.statusCode());
            return JsonMapper.builder().build().readValue(bytes,Map.class);
        }
    }
    static BenchmarkWorkerRegistration validate(Map<String,Object> registered,Map<String,Object> captured,
        UUID registration,UUID investigation,UUID snapshot,String hash,Path base,String image) {
        if(!Boolean.TRUE.equals(registered.get("verified"))||!Boolean.TRUE.equals(captured.get("verified"))
            ||!registration.toString().equals(String.valueOf(registered.get("registrationId")))
            ||!registration.toString().equals(String.valueOf(captured.get("registrationId")))
            ||!investigation.toString().equals(String.valueOf(captured.get("investigationId")))
            ||!snapshot.toString().equals(String.valueOf(captured.get("snapshotId")))
            ||hash==null||!hash.matches("[0-9a-f]{64}")||!hash.equals(captured.get("contentHash"))
            ||!base.toAbsolutePath().normalize().equals(Path.of(String.valueOf(captured.get("basePath"))).toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Benchmark snapshot registration mismatch");
        if(!(registered.get("registration") instanceof Map<?,?> c))throw new IllegalArgumentException("Missing trusted registration");
        String caseId=String.valueOf(c.get("caseId"));
        if(!caseId.matches("case-00[1-6]")||!caseId.equals(captured.get("caseId"))
            ||!("cases/"+caseId).equals(c.get("modulePath"))||!c.get("modulePath").equals(captured.get("modulePath"))
            ||!"6c4f8856bb7e4d49e953a8b086d594d2f505838d".equals(c.get("releaseCommit"))
            ||!c.get("releaseCommit").equals(captured.get("releaseCommit"))
            ||!Objects.equals(c.get("snapshotContentHash"),captured.get("benchmarkContentHash"))
            ||!"com.bugtrace.benchmark".equals(c.get("generatedTestPackage"))
            ||!TEST_CLASS.equals(c.get("generatedTestClass"))||!TEST_PATH.equals(c.get("generatedTestPath")))
            throw new IllegalArgumentException("Unsupported benchmark case or generated-test registration");
        if(!(c.get("environment") instanceof Map<?,?> env))throw new IllegalArgumentException("Missing registered environment");
        Map<String,Object> expected=new LinkedHashMap<>();
        expected.put("javaMajor",21);expected.put("buildTool","MAVEN");expected.put("containerImage",IMAGE);
        expected.put("network","none");expected.put("containerUser","1000:1000");expected.put("cpus",1);
        expected.put("memoryBytes",1073741824L);expected.put("pidsLimit",128);expected.put("jobTimeoutSeconds",120);
        expected.put("trustedFixtureOnly",true);expected.put("credentialsAllowed",false);expected.put("dockerSocketMountAllowed",false);
        expected.put("dependencyPreparation","Use an already prepared artifact-only cache copy inside the restricted container. No host settings.xml.");
        if(!IMAGE.equals(image)||!env.keySet().equals(expected.keySet()))throw new IllegalArgumentException("Unsupported benchmark environment or image");
        for(var entry:expected.entrySet()) {
            Object actual=env.get(entry.getKey()),wanted=entry.getValue();
            boolean same=wanted instanceof Number ? actual instanceof Number && new java.math.BigDecimal(actual.toString()).compareTo(new java.math.BigDecimal(wanted.toString()))==0 : wanted.equals(actual);
            if(!same)throw new IllegalArgumentException("Unsupported benchmark environment field: "+entry.getKey());
        }
        return new BenchmarkWorkerRegistration(registration,investigation,snapshot,hash,caseId,TEST_PATH,TEST_CLASS,expected);
    }
    void validateClaim(Map<String,Object> claim) {
        var mapper=JsonMapper.builder().build();var binding=mapper.valueToTree(claim.get("benchmarkBinding"));
        if(binding==null||!registrationId.toString().equals(binding.path("registrationId").asText())||!investigationId.toString().equals(binding.path("investigationId").asText())
            ||!snapshotId.toString().equals(binding.path("snapshotId").asText())||!contentHash.equals(binding.path("contentHash").asText())||!caseId.equals(binding.path("caseId").asText())
            ||!"1.0.0".equals(binding.path("collectionVersion").asText())||!"6c4f8856bb7e4d49e953a8b086d594d2f505838d".equals(binding.path("releaseCommit").asText()))throw new IllegalArgumentException("Claim differs from trusted benchmark registration");
        UUID.fromString(binding.path("runId").asText());
        int version=binding.path("sourceVersion").asInt();if(version<1||version>3)throw new IllegalArgumentException("Invalid benchmark source version");
        var registered=mapper.valueToTree(environment);var actual=binding.path("environment");
        if(actual.size()!=registered.size())throw new IllegalArgumentException("Claim environment differs");
        for(String key:environment.keySet())if(!registered.path(key).asText().equals(actual.path(key).asText()))throw new IllegalArgumentException("Claim environment differs: "+key);
        var payload=mapper.convertValue(claim.get("generatedTest"),GeneratedTestOverlay.Payload.class);GeneratedTestOverlay.validate(payload,testPath,testClass);
        if(!payload.sourceSha256().equals(binding.path("sourceSha256").asText()))throw new IllegalArgumentException("Claim source hash differs from overlay");
        if(claim.get("notAfter")==null||!java.time.Instant.parse(claim.get("notAfter").toString()).isAfter(java.time.Instant.now())||!java.time.Instant.parse(claim.get("notAfter").toString()).equals(java.time.Instant.parse(binding.path("notAfter").asText()))||!Objects.toString(claim.get("timeoutSeconds"),"").equals(binding.path("timeoutSeconds").asText()))throw new IllegalArgumentException("Claim deadline is missing or expired");
    }
    void validateJob(WorkerController.JobRequest job) {
        if(!snapshotId.equals(job.snapshotId())||job.timeoutSeconds()<1||job.timeoutSeconds()>120
            ||job.operation()!=WorkerController.Operation.RUN_SELECTED_TEST||job.generatedTest()==null
            ||!testClass.equals(job.testSelector()))throw new IllegalArgumentException("Benchmark job differs from worker registration or limits");
        GeneratedTestOverlay.validate(job.generatedTest(),testPath,testClass);
    }
}
