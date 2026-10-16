package com.bugtrace.backend;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit ancestor-only, module-scoped history. Never enumerates refs, reflogs or all branches. */
public final class SelectedHistory {
    public List<ContextDocuments.Document> read(Path repository,String snapshotCommit,String module,List<String> commits) throws Exception {
        if(commits.size()>5)throw new IllegalArgumentException("Select at most five commits");
        if(commits.isEmpty())return List.of();
        repository=repository.toAbsolutePath().normalize();
        if(!repository.equals(repository.toRealPath()))throw new IllegalArgumentException("Linked repository is not supported");
        String top=run(repository,"rev-parse","--show-toplevel").strip();
        if(!Path.of(top).toRealPath().equals(repository))throw new IllegalArgumentException("Configured repository must be its Git root");
        fullHash(snapshotCommit);if(!module.equals("."))GitSnapshot.safeRelative(module);
        String pathspec=module.equals(".")?".":module;
        var documents=new ArrayList<ContextDocuments.Document>();
        for(String commit:new LinkedHashSet<>(commits)){
            fullHash(commit);
            String resolved=run(repository,"rev-parse","--verify","--end-of-options",commit+"^{commit}").strip();
            if(!commit.equalsIgnoreCase(resolved))throw new IllegalArgumentException("Supply a full commit object ID");
            // Exit 1 is deliberately rejection, not an invitation to inspect the other branch.
            run(repository,"merge-base","--is-ancestor",commit,snapshotCommit);
            String[] parents=run(repository,"rev-list","--parents","-n","1",commit).strip().split("\\s+");
            if(parents.length>2)throw new IllegalArgumentException("Merge commits are unsupported in the initial history importer");
            String message=run(repository,"show","-s","--format=%B",commit);
            String diff=run(repository,"diff-tree","--root","--no-commit-id","--patch","--no-ext-diff","--no-textconv","--no-renames","--unified=3","-r",commit,"--",pathspec);
            if(diff.isBlank())throw new IllegalArgumentException("Selected commit does not change the captured module");
            documents.add(new ContextDocuments.Document("HISTORICAL_COMMIT","git:"+commit+":message",commit,"COMMIT_MESSAGE_LINE","Selected commit "+commit,message));
            documents.add(new ContextDocuments.Document("HISTORICAL_DIFF","git:"+commit+":"+module+":diff",commit,"DIFF_LINE","Selected diff "+commit,diff));
        }
        return List.copyOf(documents);
    }
    private static void fullHash(String hash){if(hash==null||!hash.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))throw new IllegalArgumentException("History requires full commit hashes");}
    static String run(Path repo,String... arguments) throws Exception {
        var command=new ArrayList<>(List.of("git","--no-replace-objects","--literal-pathspecs","-c","core.pager=cat","-c","color.ui=false","-C",repo.toString()));command.addAll(List.of(arguments));
        var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key->key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().put("GIT_NO_LAZY_FETCH","1");builder.environment().put("GIT_OPTIONAL_LOCKS","0");
        var process=builder.start();var reader=Executors.newSingleThreadExecutor();
        var output=reader.submit(()->{try(var stream=process.getInputStream()){byte[] bytes=stream.readNBytes(256*1024+1);if(bytes.length>256*1024){process.destroyForcibly();throw new IOException("Selected Git output exceeds 256 KiB");}return bytes;}});
        try{
            if(!process.waitFor(15,TimeUnit.SECONDS))throw new IOException("Git history read timed out");
            byte[] bytes=output.get(3,TimeUnit.SECONDS);
            if(process.exitValue()!=0)throw new IllegalArgumentException("History selection rejected: commit must exist locally and be an ancestor of the captured snapshot");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }finally{process.destroyForcibly();reader.shutdownNow();}
    }
}
