package com.bugtrace.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, explicitly pinned public release validation. No source execution. */
public final class BenchmarkCatalog {
    public static final String VERSION="benchmark-registration-v1";
    public static final String MANIFEST="21dc97d9f3d7031ef883ffa3fa959ebfed7c7fcdd4141cdc06967aaa207359ce";
    public static final String IMAGE="maven@sha256:5496bf5b90ea4ad5af01d63be191f946f15ef5c375a6dd48fc21482a43108ca5";
    private static final Map<String,String> CLASSES=Map.of("case-001","CheckoutProcessor","case-002","RequestProcessor","case-003","SharedCounter","case-004","DeadlineService","case-005","CheckoutFacade","case-006","NotificationDispatcher");
    private final Path root; private final String commit,manifest;
    private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkCatalog(Path root,String commit,String manifest) {
        this.root=root.toAbsolutePath().normalize();this.commit=commit;this.manifest=manifest;
        if(commit==null||!commit.matches("[0-9a-f]{40}")||manifest==null||!manifest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Configure full release commit and manifest SHA256");
    }
    public record Registration(String validatorVersion,String collectionVersion,String releaseCommit,String manifestSha256,
        String repository,String caseId,String modulePath,String snapshotContentHash,List<String> allowedProductionPaths,
        String generatedTestPackage,String generatedTestClass,String generatedTestPath,Map<String,Object> environment,Map<String,String> files) {}
    public List<Registration> validate() throws Exception {
        real(root);if(!Files.isDirectory(root))throw new IllegalArgumentException("Public repository directory missing");
        real(root.resolve(".git"));if(!Files.isDirectory(root.resolve(".git")))throw new IllegalArgumentException("Use a standalone public repository with its own .git directory");
        if(!Path.of(text(git("rev-parse","--show-toplevel"))).toRealPath().equals(root))throw new IllegalArgumentException("Use repository root");
        if(!commit.equals(text(git("rev-parse","--verify","--end-of-options","benchmark-v1.0.0^{commit}"))))throw new IllegalArgumentException("Release tag does not match pinned commit");
        if(!commit.equals(text(git("rev-parse","--verify","HEAD^{commit}"))))throw new IllegalArgumentException("Public HEAD differs from released commit");
        byte[] bytes=read(root.resolve("collection.json"));if(!manifest.equals(hash(bytes)))throw new IllegalArgumentException("Public manifest hash changed");
        JsonNode collection=json.readTree(bytes);
        if(!"1.0.0".equals(collection.path("version").asString())||!"bugtrace-java-benchmarks".equals(collection.path("collection").asString())||!collection.path("cases").isArray()||collection.path("cases").size()!=6)throw new IllegalArgumentException("Expected pinned six-case collection 1.0.0");
        var expected=new TreeSet<String>(List.of("collection.json","README.md",".gitignore"));
        var ids=new HashSet<String>();var result=new ArrayList<Registration>();
        for(JsonNode c:collection.path("cases")) {
            String id=c.path("id").asString(),type=CLASSES.get(id),module="cases/"+id;
            if(type==null||!ids.add(id)||!module.equals(c.path("module").asString())||!"1.0.0".equals(c.path("version").asString()))throw new IllegalArgumentException("Unknown or duplicate case/module");
            String source="src/main/java/com/bugtrace/benchmark/"+type+".java";
            var pinned=new TreeMap<String,String>();
            if(!c.path("files").isObject())throw new IllegalArgumentException("Missing file hashes");
            for(var entry:c.path("files").properties()) {
                String relative=relative(entry.getKey()),digest=entry.getValue().asString();
                if(!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid file hash");
                byte[] content=read(root.resolve(module).resolve(relative));
                if(!hash(content).equals(digest))throw new IllegalArgumentException("Source bytes changed: "+module+"/"+relative);
                pinned.put(relative,digest);expected.add(module+"/"+relative);
            }
            if(!pinned.keySet().equals(Set.of("case.json","environment.json","pom.xml",source)))throw new IllegalArgumentException("Unexpected public case file set");
            String treeHash=hash(json.writeValueAsString(pinned).getBytes(StandardCharsets.UTF_8));
            if(!treeHash.equals(c.path("snapshotContentHash").asString()))throw new IllegalArgumentException("Case content hash mismatch");
            JsonNode info=json.readTree(read(root.resolve(module).resolve("case.json")));
            if(!id.equals(info.path("id").asString())||!"1.0.0".equals(info.path("version").asString())||info.path("allowedChangeScope").size()!=1||!source.equals(info.path("allowedChangeScope").path(0).asString()))throw new IllegalArgumentException("Case identity or allowed scope mismatch");
            JsonNode env=json.readTree(read(root.resolve(module).resolve("environment.json")));environment(env);
            String test="GeneratedBenchmarkReproductionTest",pkg="com.bugtrace.benchmark";
            @SuppressWarnings("unchecked") Map<String,Object> environment=json.convertValue(env,Map.class);
            result.add(new Registration(VERSION,"1.0.0",commit,manifest,root.toString(),id,module,treeHash,List.of(source),pkg,pkg+"."+test,"src/test/java/com/bugtrace/benchmark/"+test+".java",environment,Map.copyOf(pinned)));
        }
        var actual=new TreeSet<String>();scan(root,actual);
        if(!expected.equals(actual))throw new IllegalArgumentException("Unexpected or missing public files; keep private/generated material outside this repository");
        var tracked=new TreeSet<String>();
        for(String row:new String(git("ls-tree","-r","-z","--full-tree",commit),StandardCharsets.UTF_8).split("\u0000")) {
            if(row.isEmpty())continue;int tab=row.indexOf('\t');if(tab<0)throw new IllegalArgumentException("Invalid release tree");
            String[] header=row.substring(0,tab).split(" ");String path=relative(row.substring(tab+1));
            if(header.length!=3||!header[1].equals("blob")||!Set.of("100644","100755").contains(header[0])||!tracked.add(path))throw new IllegalArgumentException("Linked, duplicate or unsupported release entry");
        }
        if(!tracked.equals(expected))throw new IllegalArgumentException("Committed public tree differs from registered file set");
        for(String path:expected)if(!Arrays.equals(read(root.resolve(path)),git("show",commit+":"+path)))throw new IllegalArgumentException("Working bytes differ from release: "+path);
        return List.copyOf(result);
    }
    public static String relative(String path) {
        if(path==null||path.isBlank()||path.contains("\\")||path.contains(":")||path.startsWith("/")||path.endsWith("/"))throw new IllegalArgumentException("Unsafe path");
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" "))throw new IllegalArgumentException("Unsafe path segment");
        return path;
    }
    public static void real(Path path) throws IOException {
        Path full=path.toAbsolutePath().normalize();
        for(Path p=full;p!=null;p=p.getParent()) {
            if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p)||!p.toRealPath().equals(p))throw new IllegalArgumentException("Missing or linked path: "+p);
        }
    }
    private byte[] read(Path path) throws IOException {real(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>262144)throw new IllegalArgumentException("Invalid or oversized public file");return Files.readAllBytes(path);}
    private void scan(Path directory,Set<String> files) throws IOException {
        try(var entries=Files.newDirectoryStream(directory)) {for(Path path:entries) {
            if(directory.equals(root)&&path.getFileName().toString().equals(".git"))continue;
            real(path);if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))scan(path,files);
            else if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))files.add(root.relativize(path).toString().replace('\\','/'));
            else throw new IllegalArgumentException("Special public file rejected");
            if(files.size()>100)throw new IllegalArgumentException("Unexpected public file count");
        }}
    }
    public static void environment(JsonNode env) {
        if(!env.isObject()||env.size()!=13||env.path("javaMajor").asInt()!=21||!"MAVEN".equals(env.path("buildTool").asString())||!IMAGE.equals(env.path("containerImage").asString())||!"none".equals(env.path("network").asString())||!"1000:1000".equals(env.path("containerUser").asString())||env.path("cpus").asDouble()!=1||env.path("memoryBytes").asLong()!=1073741824L||env.path("pidsLimit").asInt()!=128||env.path("jobTimeoutSeconds").asInt()!=120||!env.path("trustedFixtureOnly").asBoolean()||!env.path("credentialsAllowed").isBoolean()||env.path("credentialsAllowed").asBoolean()||!env.path("dockerSocketMountAllowed").isBoolean()||env.path("dockerSocketMountAllowed").asBoolean()||!"Use an already prepared artifact-only cache copy inside the restricted container. No host settings.xml.".equals(env.path("dependencyPreparation").asString()))throw new IllegalArgumentException("Execution environment differs from approved benchmark policy");
    }
    public static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static String text(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8).strip();}
    private byte[] git(String...args) throws Exception {
        var command=new ArrayList<>(List.of("git","--no-pager","-C",root.toString()));command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectErrorStream(true);builder.environment().put("GIT_NO_REPLACE_OBJECTS","1");builder.environment().put("GIT_OPTIONAL_LOCKS","0");
        Process p=builder.start();try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var output=executor.submit(()->p.getInputStream().readNBytes(1048577));
            if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalArgumentException("Git validation timed out");}
            byte[] bytes=output.get(5,TimeUnit.SECONDS);if(bytes.length>1048576)throw new IllegalArgumentException("Git output exceeds limit");if(p.exitValue()!=0)throw new IllegalArgumentException("Git release validation failed: "+text(bytes).substring(0,Math.min(400,text(bytes).length())));return bytes;
        }finally{p.destroyForcibly();}
    }
}
