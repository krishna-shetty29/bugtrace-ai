package com.bugtrace.backend;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Filesystem and unified-diff validation for the trusted local fixture review. */
public final class PatchReviewFiles {
    private PatchReviewFiles() {}
    public static final String PREFIX="src/main/java/com/bugtrace/fixtures/checkout_timeout/";
    public static final Set<String> ALLOWED=Set.of(PREFIX+"PaymentLedger.java",PREFIX+"FakePaymentGateway.java",PREFIX+"CheckoutService.java");
    public static Path safe(Path root,String relative) throws Exception {
        Path absolute=root.toAbsolutePath().normalize();
        if(!absolute.equals(absolute.toRealPath()))throw new IllegalArgumentException("Linked or unexpected root");
        if(relative.isBlank()||relative.contains("\\")||relative.contains(":")||relative.startsWith("/")||relative.contains("\0"))throw new IllegalArgumentException("Unsafe relative path");
        Path resolved=absolute.resolve(relative).normalize();
        if(!resolved.startsWith(absolute)||resolved.equals(absolute))throw new IllegalArgumentException("Path escapes root");
        for(Path part:absolute.relativize(resolved)){absolute=absolute.resolve(part);if(Files.isSymbolicLink(absolute))throw new IllegalArgumentException("Links are prohibited");}
        if(!Files.isRegularFile(resolved,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Missing regular file");
        return resolved;
    }
    public static byte[] read(Path path,int limit) throws Exception {
        if(Files.size(path)>limit)throw new IllegalArgumentException("File size limit exceeded");
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>limit)throw new IllegalArgumentException("File size limit exceeded");return bytes;
    }
    public static String text(Path path,int limit) throws Exception {return new String(read(path,limit),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","");}
    public static String sha(byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static String sha(Path path) throws Exception{return sha(read(path,32_000_000));}
    static List<String> lines(String text){var value=text.replace("\r\n","\n");if(value.isEmpty())return new ArrayList<>();if(value.endsWith("\n"))value=value.substring(0,value.length()-1);return new ArrayList<>(Arrays.asList(value.split("\n",-1)));}
    public static Set<String> validateDiff(String diff,Map<String,String> originals,Map<String,String> proposed) {
        var lines=lines(diff);int cursor=0;var changed=new LinkedHashSet<String>();
        var hunk=Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?");
        while(cursor<lines.size()) {
            String head=lines.get(cursor++);if(!head.startsWith("diff --git a/"))throw new IllegalArgumentException("Unsupported patch header");
            String path=head.substring("diff --git a/".length()).split(" b/",-1)[0];
            if(!ALLOWED.contains(path)||!head.equals("diff --git a/"+path+" b/"+path)||!changed.add(path))throw new IllegalArgumentException("Unapproved or duplicate changed path");
            if(cursor<lines.size()&&lines.get(cursor).matches("index [0-9a-f]{7,64}\\.\\.[0-9a-f]{7,64} 100644"))cursor++;
            if(cursor+1>=lines.size()||!lines.get(cursor++).equals("--- a/"+path)||!lines.get(cursor++).equals("+++ b/"+path))throw new IllegalArgumentException("Unsupported old/new paths");
            String original=Objects.requireNonNull(originals.get(path)).replace("\r\n","\n"),proposal=Objects.requireNonNull(proposed.get(path)).replace("\r\n","\n");
            var old=lines(original);var rebuilt=new ArrayList<String>();int oldCursor=0;boolean hadHunk=false,oldNoNewline=false,newNoNewline=false;
            while(cursor<lines.size()&&!lines.get(cursor).startsWith("diff --git ")) {
                var match=hunk.matcher(lines.get(cursor++));if(!match.matches())throw new IllegalArgumentException("Unsupported hunk");hadHunk=true;
                int oldStart=Integer.parseInt(match.group(1)),oldCount=match.group(2)==null?1:Integer.parseInt(match.group(2));
                int newStart=Integer.parseInt(match.group(3)),newCount=match.group(4)==null?1:Integer.parseInt(match.group(4));
                int target=oldCount==0?oldStart:oldStart-1;
                if(target<oldCursor||target>old.size())throw new IllegalArgumentException("Hunk out of range");
                rebuilt.addAll(old.subList(oldCursor,target));oldCursor=target;
                if(rebuilt.size()!=(newCount==0?newStart:newStart-1))throw new IllegalArgumentException("New hunk position mismatch");
                int consumed=0,produced=0;char previous=0;
                while(cursor<lines.size()&&!lines.get(cursor).startsWith("@@ ")&&!lines.get(cursor).startsWith("diff --git ")) {
                    String entry=lines.get(cursor++);
                    if(entry.equals("\\ No newline at end of file")) {
                        if(previous==0)throw new IllegalArgumentException("Unexpected newline marker");
                        if(previous=='-'||previous==' '){if(original.endsWith("\n")||oldCursor!=old.size())throw new IllegalArgumentException("Invalid old newline marker");oldNoNewline=true;}
                        if(previous=='+'||previous==' '){if(proposal.endsWith("\n")||rebuilt.size()!=lines(proposal).size())throw new IllegalArgumentException("Invalid new newline marker");newNoNewline=true;}
                        previous=0;continue;
                    }
                    if(entry.isEmpty())throw new IllegalArgumentException("Invalid empty hunk line");char kind=entry.charAt(0);String body=entry.substring(1);previous=kind;
                    if(kind==' '||kind=='-'){if(oldCursor>=old.size()||!old.get(oldCursor++).equals(body))throw new IllegalArgumentException("Hunk does not match base");consumed++;}
                    if(kind==' '||kind=='+'){rebuilt.add(body);produced++;}
                    if(kind!=' '&&kind!='-'&&kind!='+')throw new IllegalArgumentException("Unsupported hunk marker");
                }
                if(consumed!=oldCount||produced!=newCount)throw new IllegalArgumentException("Hunk count mismatch");
            }
            boolean touchedEnd=oldCursor==old.size();
            if(touchedEnd&&!original.endsWith("\n")&&!oldNoNewline)throw new IllegalArgumentException("Missing old newline marker");
            rebuilt.addAll(old.subList(oldCursor,old.size()));
            String rebuiltText=String.join("\n",rebuilt)+((touchedEnd?!newNoNewline:original.endsWith("\n"))?"\n":"");
            if(!hadHunk||!rebuiltText.equals(proposal)||original.equals(proposal))throw new IllegalArgumentException("Diff and proposed contents disagree");
        }
        if(changed.isEmpty())throw new IllegalArgumentException("Empty patch");return changed;
    }
}
