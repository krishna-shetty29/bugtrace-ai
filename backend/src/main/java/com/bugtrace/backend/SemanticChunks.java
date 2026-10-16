package com.bugtrace.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reads captured source only. Does not compile, run plugins, or modify the base. */
public final class SemanticChunks {
    public static final String VERSION="method-section-v1";
    public static final int MAX_CHUNKS=512, MAX_CHARS=1200;
    public record Chunk(String key,String filePath,int startLine,int endLine,String kind,
        String name,boolean isTest,String text,String textHash) {}
    public record Corpus(List<Chunk> chunks,List<String> diagnostics) {}

    public Corpus read(Path base) throws Exception {
        base=base.toAbsolutePath().normalize();
        if(!base.equals(base.toRealPath())) throw new IllegalArgumentException("Linked base is not supported");
        var index=new JavaSymbolIndexer().index(base);
        var java=new HashMap<String,JavaSymbolIndexer.SourceFile>();
        index.files().forEach(file->java.put(file.filePath(),file));
        var chunks=new ArrayList<Chunk>();var diagnostics=new ArrayList<String>();
        List<Path> paths;
        try(var walk=Files.walk(base)){paths=walk.limit(2002).sorted().toList();}
        if(paths.size()>2001)throw new IllegalArgumentException("Use a smaller snapshot: too many entries");
        long bytes=0;
        for(Path path:paths){
            if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path.toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Linked snapshot entry is not supported");
            if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Non-regular snapshot entry");
            String file=base.relativize(path).toString().replace('\\','/');
            long size=Files.size(path);bytes+=size;
            if(size>5L*1024*1024||bytes>64L*1024*1024)throw new IllegalArgumentException("Snapshot exceeds source size limit");
            String lower=file.toLowerCase(Locale.ROOT);
            boolean supported=lower.endsWith(".java")||lower.endsWith(".md")||lower.endsWith(".txt")||lower.endsWith(".properties")||lower.endsWith(".xml")||lower.endsWith(".yaml")||lower.endsWith(".yml");
            if(!supported)continue;
            String source;
            try{source=Files.readString(path,StandardCharsets.UTF_8);}
            catch(java.io.IOException ex){diagnostics.add(file+": unreadable UTF-8: "+ex.getMessage());continue;}
            String[] lines=source.split("\\R",-1);boolean[] used=new boolean[lines.length];
            var parsed=java.get(file);
            if(parsed!=null&&parsed.parsed()){
                var methods=parsed.symbols().stream().filter(s->Set.of("METHOD","CONSTRUCTOR","ANNOTATION_MEMBER").contains(s.kind()))
                    .sorted(Comparator.comparingInt(s->s.endLine()-s.startLine())).toList();
                for(var method:methods){
                    add(chunks,file,lines,method.startLine(),method.endLine(),method.kind(),method.owner()+"."+method.signature());
                    Arrays.fill(used,Math.max(0,method.startLine()-1),Math.min(lines.length,method.endLine()),true);
                }
            }else if(parsed!=null)diagnostics.add(file+": parser failed; retained as line chunks: "+parsed.failures());
            // Remaining Java ranges contain package/imports, class declarations and fields.
            // Markdown headings delimit document sections; large sections split without losing text.
            for(int start=0;start<lines.length;){
                if(used[start]){start++;continue;}
                int end=start+1;
                while(end<lines.length&&!used[end]&&!(lower.endsWith(".md")&&lines[end].startsWith("#")))end++;
                add(chunks,file,lines,start+1,end,parsed==null?"DOCUMENT_SECTION":"CLASS_CONTEXT",file);
                start=end;
            }
        }
        if(chunks.isEmpty())throw new IllegalArgumentException("No supported non-empty source or documentation chunks");
        return new Corpus(List.copyOf(chunks),List.copyOf(diagnostics));
    }

    static void add(List<Chunk> out,String file,String[] lines,int first,int last,String kind,String name) throws Exception {
        StringBuilder buffer=new StringBuilder();int start=first;
        for(int line=first;line<=Math.min(last,lines.length);line++){
            String value=lines[line-1]+"\n";
            if(value.length()>MAX_CHARS)throw new IllegalArgumentException(file+":"+line+" exceeds chunk line limit; use a smaller source line");
            if(buffer.length()+value.length()>MAX_CHARS){emit(out,file,start,line-1,kind,name,buffer.toString());buffer.setLength(0);start=line;}
            buffer.append(value);
        }
        emit(out,file,start,Math.min(last,lines.length),kind,name,buffer.toString());
    }
    private static void emit(List<Chunk> out,String file,int first,int last,String kind,String name,String text) throws Exception {
        if(text.isBlank())return;
        if(out.size()>=MAX_CHUNKS)throw new IllegalArgumentException("More than 512 chunks; import a smaller module");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        String key=file+":"+first+":"+last+":"+kind;
        if(out.stream().noneMatch(c->c.key().equals(key)))out.add(new Chunk(key,file,first,last,kind,name,file.contains("/test/"),text,hash));
    }
}
