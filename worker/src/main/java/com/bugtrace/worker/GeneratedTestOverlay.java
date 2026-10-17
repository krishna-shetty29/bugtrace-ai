package com.bugtrace.worker;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class GeneratedTestOverlay {
    public static final String CLASS="com.bugtrace.fixtures.checkout_timeout.GeneratedCheckoutReproductionTest";
    public static final String PATH="src/test/java/com/bugtrace/fixtures/checkout_timeout/GeneratedCheckoutReproductionTest.java";
    public record Payload(String path,String className,String source,String sourceSha256,String expectedFailureSignature) {}
    public static void validate(Payload payload) {
        if(payload==null || !PATH.equals(payload.path()) || !CLASS.equals(payload.className()) || payload.source()==null
            || payload.source().length()>12000 || payload.source().getBytes(StandardCharsets.UTF_8).length>16384
            || !sha(payload.source()).equals(payload.sourceSha256()))throw new IllegalArgumentException("Invalid generated-test overlay path, class, size or source hash");
    }
    public static String install(Path experiment,Payload payload) throws Exception {
        validate(payload);SnapshotFiles.requireRealPath(experiment);
        Path root=experiment.toAbsolutePath().normalize(),target=root.resolve(PATH).normalize();
        if(!target.startsWith(root))throw new IllegalArgumentException("Generated path escapes experiment");
        Path walk=root;
        for(Path part:root.relativize(target.getParent())) {
            walk=walk.resolve(part);
            if(!Files.exists(walk,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(walk);
            SnapshotFiles.requireRealPath(walk);
            if(!Files.isDirectory(walk,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Invalid generated-test parent");
        }
        Files.writeString(target,payload.source(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        SnapshotFiles.requireRealPath(target);
        String hash=ExecutionEvidence.sha256(target);
        if(!hash.equals(payload.sourceSha256()))throw new IllegalArgumentException("Generated source write hash mismatch");return hash;
    }
    public static String sha(String source) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
