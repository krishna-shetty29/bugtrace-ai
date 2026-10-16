package com.bugtrace.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Provenance is data, not a claim that historical material describes current code. */
public final class ContextDocuments {
    public static final String VERSION="document-history-v1";
    public record Document(String origin,String sourceRef,String revision,String lineKind,String title,String body){}
    public record Chunk(String origin,String sourceRef,String revision,String lineKind,String title,
        int startLine,int endLine,String documentHash,String chunkHash,String text){}
    public static String hash(String input){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public static boolean supported(String path){
        String name=path.toLowerCase(Locale.ROOT);
        if(Arrays.stream(name.split("/")).anyMatch(part->part.equals(".git")||part.startsWith(".env")||part.contains("credential")||part.equals("settings.xml")))return false;
        return name.endsWith(".md")||name.endsWith(".txt")||name.endsWith(".properties")||name.endsWith(".yaml")||name.endsWith(".yml")||name.endsWith(".json")||name.endsWith(".toml")||name.endsWith(".ini")||name.equals("pom.xml");
    }
    public static Document snapshot(Path base,String path,String commit) throws Exception {
        GitSnapshot.safeRelative(path);
        if(!supported(path))throw new IllegalArgumentException("Select a supported documentation/configuration file");
        Path target=base.resolve(path).normalize();
        if(!target.startsWith(base)||!target.toRealPath().equals(target.toAbsolutePath().normalize())||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Invalid captured source path");
        if(Files.size(target)>256*1024)throw new IllegalArgumentException("Selected document exceeds 256 KiB");
        String body=Files.readString(target,StandardCharsets.UTF_8);
        // Reject literal credential assignments; environment placeholders remain useful evidence.
        for(String line:body.split("\\R"))if(line.matches("(?i).*\\b(password|secret|api[-_]?key|access[-_]?token)\\s*[:=].*")&&!line.contains("${")&&!line.strip().startsWith("#"))
            throw new IllegalArgumentException("Selected configuration may contain literal credentials: "+path);
        boolean doc=path.toLowerCase(Locale.ROOT).matches(".*\\.(md|txt)$");
        return new Document(doc?"SNAPSHOT_DOCUMENT":"SNAPSHOT_CONFIG",path,commit,"SOURCE_LINE",path,body);
    }
    public static List<Chunk> chunks(List<Document> documents){
        var output=new ArrayList<Chunk>();
        for(var document:documents){
            if(document.body().getBytes(StandardCharsets.UTF_8).length>256*1024)throw new IllegalArgumentException("Document exceeds 256 KiB: "+document.sourceRef());
            String body=document.body().replace("\r\n","\n").replace('\r','\n');
            String docHash=hash(document.body());String[] lines=body.split("\n",-1);int lineCount=lines.length-(body.endsWith("\n")?1:0);int first=1;StringBuilder text=new StringBuilder();
            for(int n=0;n<lineCount;n++){
                String line=lines[n]+"\n";
                if(line.length()>1200)throw new IllegalArgumentException("Source line exceeds 1200 characters: "+document.sourceRef()+":"+(n+1));
                boolean section=lines[n].startsWith("#")&&document.origin().equals("SNAPSHOT_DOCUMENT");
                if(!text.isEmpty()&&(text.length()+line.length()>1200||section)){
                    emit(output,document,first,n,docHash,text.toString());text.setLength(0);first=n+1;
                }
                text.append(line);
            }
            emit(output,document,first,lineCount,docHash,text.toString());
        }
        if(output.size()>512)throw new IllegalArgumentException("More than 512 supplementary chunks; select fewer sources");
        return List.copyOf(output);
    }
    private static void emit(List<Chunk> out,Document doc,int start,int end,String docHash,String text){
        if(text.isBlank())return;
        out.add(new Chunk(doc.origin(),doc.sourceRef(),doc.revision(),doc.lineKind(),doc.title(),start,end,docHash,hash(text),text));
    }
    public static String prompt(Chunk chunk){
        String title=chunk.origin()+" "+chunk.sourceRef();
        return "title: "+title.substring(0,Math.min(title.length(),240))+" | text: "+chunk.text();
    }
}
