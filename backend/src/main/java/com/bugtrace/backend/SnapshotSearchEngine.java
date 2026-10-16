package com.bugtrace.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded literal search over a trusted, immutable local snapshot. */
public final class SnapshotSearchEngine {
    public enum Kind { ALL, CLASS, METHOD, FILE, TEXT, ERROR }
    public record Hit(String kind,String origin,String filePath,String executionId,int line,int column,
        String name,String snippet,int score) {}
    public record Notice(String filePath,String message) {}
    public record Scan(int sourceFiles,int textFiles,List<Notice> notices) {}
    public record Query(String text,Kind kind,int limit,Pattern literal,Pattern pathLiteral) {
        public static Query create(String input,Kind kind,int limit) {
            String text=input==null?"":input.strip();
            if (text.length()<2 || text.length()>200 || text.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Enter 2-200 characters without control characters");
            if (kind==null || limit<1 || limit>100) throw new IllegalArgumentException("Search kind is required; limit must be 1-100");
            int flags=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
            return new Query(text,kind,limit,Pattern.compile(Pattern.quote(text),flags),Pattern.compile(Pattern.quote(text.replace('\\','/')),flags));
        }
        boolean includes(Kind requested) { return kind==Kind.ALL || kind==requested; }
    }
    private static final Set<String> TEXT_EXTENSIONS=Set.of("java","xml","properties","md","txt","yaml","yml","json","sql","kt","kts","gradle","js","jsx","ts","tsx","css","html");
    private static final Comparator<Hit> BEST=Comparator.comparingInt(Hit::score).reversed()
        .thenComparing(Hit::origin).thenComparing(Hit::filePath).thenComparingInt(Hit::line).thenComparingInt(Hit::column)
        .thenComparing(Hit::executionId).thenComparing(Hit::kind).thenComparing(Hit::name);
    public static final class Matches {
        private final int limit;
        private final PriorityQueue<Hit> best=new PriorityQueue<>(BEST.reversed());
        private long count;
        public Matches(int limit) { this.limit=limit; }
        void add(Hit hit) { count++;if(best.size()<limit)best.add(hit);else if(BEST.compare(hit,best.peek())<0){best.remove();best.add(hit);} }
        public long totalMatches() { return count; }
        public List<Hit> hits() { return best.stream().sorted(BEST).toList(); }
    }
    public void symbols(JavaSymbolIndexer.Index index,Query query,Matches matches) {
        if (index==null) return;
        for (var file:index.files()) {
            for (var symbol:file.symbols()) {
                Kind kind=Set.of("CLASS","INTERFACE","RECORD","ENUM","ANNOTATION_TYPE").contains(symbol.kind())?Kind.CLASS:
                    Set.of("METHOD","CONSTRUCTOR","ANNOTATION_MEMBER").contains(symbol.kind())?Kind.METHOD:null;
                if (kind==null || !query.includes(kind)) continue;
                if (!query.literal.matcher(symbol.owner()+"."+symbol.name()).find()) continue;
                int score=(kind==Kind.CLASS?120:110)-(symbol.name().equalsIgnoreCase(query.text)?0:20);
                matches.add(new Hit(symbol.kind(),"SYMBOL",symbol.filePath(),"",symbol.startLine(),symbol.startColumn(),symbol.name(),
                    clip(symbol.owner()+" :: "+symbol.signature(),0),score));
            }
            if (query.includes(Kind.ERROR)) for (var failure:file.failures()) {
                var found=query.literal.matcher(failure.message());
                if(found.find())matches.add(new Hit("ERROR","PARSER",file.filePath(),"",failure.line()==null?0:failure.line(),failure.column()==null?0:failure.column(),failure.phase(),clip(failure.message(),found.start()),75));
            }
        }
    }
    public Scan source(Path base,Query query,Matches matches) throws IOException {
        if (!query.includes(Kind.FILE) && !query.includes(Kind.TEXT)) return new Scan(0,0,List.of());
        base=base.toAbsolutePath().normalize();
        if (!base.toRealPath().equals(base)) throw new IOException("Search base must be a real directory");
        List<Path> paths;
        try (var stream=Files.walk(base)) { paths=stream.limit(2002).sorted().toList(); }
        if(paths.size()>2001)throw new IOException("Snapshot exceeds the initial search entry limit");
        int files=0,textFiles=0;long bytes=0;List<Notice> notices=new ArrayList<>();
        for(Path path:paths) {
            if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException("Linked paths are unsupported");
            if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Non-regular snapshot entry");
            if(++files>1000)throw new IOException("Snapshot exceeds 1000 files");
            long size=Files.size(path);bytes+=size;
            if(size>5L*1024*1024||bytes>64L*1024*1024)throw new IOException("Snapshot exceeds search size limits");
            String relative=base.relativize(path).toString().replace('\\','/');
            if(query.includes(Kind.FILE)&&query.pathLiteral.matcher(relative).find())
                matches.add(new Hit("FILE","SNAPSHOT",relative,"",0,0,path.getFileName().toString(),relative,80));
            String name=path.getFileName().toString();int dot=name.lastIndexOf('.');
            if(!query.includes(Kind.TEXT)||dot<0||!TEXT_EXTENSIONS.contains(name.substring(dot+1).toLowerCase(Locale.ROOT)))continue;
            try(BufferedReader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
                lines(reader,query,matches,"TEXT","SOURCE",relative,"",60);textFiles++;
            } catch(IOException exception) { notices.add(new Notice(relative,"Text could not be read as UTF-8: "+exception.getClass().getSimpleName())); }
        }
        return new Scan(files,textFiles,List.copyOf(notices));
    }
    public void log(String executionId,String stream,String value,Query query,Matches matches) throws IOException {
        if(value==null || !query.includes(Kind.ERROR))return;
        // Stored logs are already bounded by the worker; enforce a reader bound too.
        if(value.length()>2097152)value=value.substring(0,2097152);
        try(var reader=new BufferedReader(new StringReader(value))) {
            lines(reader,query,matches,"ERROR",stream,"",executionId,70);
        }
    }
    private void lines(BufferedReader reader,Query query,Matches matches,String kind,String origin,String path,String execution,int score) throws IOException {
        String line;int number=0;
        while((line=reader.readLine())!=null){number++;var found=query.literal.matcher(line);if(found.find())
            matches.add(new Hit(kind,origin,path,execution,number,found.start()+1,"",clip(line,found.start()),score));}
    }
    private static String clip(String line,int match) { int start=Math.max(0,match-80),end=Math.min(line.length(),Math.max(match+180,start+240));return (start>0?"…":"")+line.substring(start,end)+(end<line.length()?"…":""); }
}
