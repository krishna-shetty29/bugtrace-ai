package com.bugtrace.backend;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Checks benchmark's manifest hash separately from GitSnapshot's platform hash. */
public final class BenchmarkSnapshotFiles {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private BenchmarkSnapshotFiles(){}
    public static String verify(Path base,BenchmarkCatalog.Registration registration)throws Exception {
        BenchmarkCatalog.real(base);var actual=new TreeMap<String,String>();
        try(var paths=Files.walk(base)) {for(Path path:paths.toList()) {
            BenchmarkCatalog.real(path);
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
                String relative=base.relativize(path).toString().replace('\\','/');BenchmarkCatalog.relative(relative);
                if(Files.size(path)>262144)throw new IllegalArgumentException("Oversized captured file");
                actual.put(relative,BenchmarkCatalog.hash(Files.readAllBytes(path)));
            }else if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Special captured file");
        }}
        if(!actual.equals(new TreeMap<>(registration.files())))throw new IllegalArgumentException("Captured file set or bytes differ from registered case");
        String hash=BenchmarkCatalog.hash(JSON.writeValueAsString(actual).getBytes(StandardCharsets.UTF_8));
        if(!hash.equals(registration.snapshotContentHash()))throw new IllegalArgumentException("Captured benchmark content hash mismatch");
        return hash;
    }
}
