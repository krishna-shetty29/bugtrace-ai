package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;

/** Validate against captured bytes; an internally consistent indexed snippet alone is insufficient. */
public final class DiagnosisCitations {
    public static void verifyEvidence(Path base,AiEvidenceService.Evidence evidence) throws Exception {
        if(!"CODE".equals(evidence.reference().kind())||!"CURRENT_SNAPSHOT_CODE".equals(evidence.origin())||!"SOURCE_LINE".equals(evidence.lineKind())||!evidence.sourceRef().endsWith(".java"))throw new IllegalArgumentException("Diagnosis citations require current Java source");
        String range=range(base,evidence.sourceRef(),evidence.startLine(),evidence.endLine());
        if(!ContextDocuments.hash(range+"\n").equals(evidence.sourceHash())||!Objects.equals(range+"\n",evidence.text()))throw new IllegalArgumentException("Indexed source does not match captured file lines");
    }
    public static Map<String,Object> verify(Path base,DiagnosisDocument.CodeLocation cite,AiEvidenceService.Evidence evidence) throws Exception {
        verifyEvidence(base,evidence);
        if(!cite.evidenceId().equals(evidence.id())||!cite.filePath().equals(evidence.sourceRef())||!cite.revision().equals(evidence.revision())||cite.startLine()<evidence.startLine()||cite.endLine()>evidence.endLine())throw new IllegalArgumentException("Citation escaped the selected source chunk");
        String actual=range(base,cite.filePath(),cite.startLine(),cite.endLine());
        if(!actual.equals(cite.quote()))throw new IllegalArgumentException("Quoted source does not match the exact captured lines");
        return Map.of("evidenceId",evidence.id(),"filePath",evidence.sourceRef(),"revision",evidence.revision(),"lineKind","SOURCE_LINE",
            "startLine",cite.startLine(),"endLine",cite.endLine(),"quote",actual,"chunkHash",evidence.sourceHash(),"quoteHash",ContextDocuments.hash(actual));
    }
    public static String range(Path base,String path,int first,int last) throws Exception {
        if(first<1||last<first||last-first>2000)throw new IllegalArgumentException("Invalid source range");
        Path root=base.toAbsolutePath().normalize();
        if(!root.equals(root.toRealPath()))throw new IllegalArgumentException("Linked base rejected");
        Path file=root.resolve(GitSnapshot.safeRelative(path)).normalize();
        if(!file.startsWith(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.equals(file.toRealPath())||Files.size(file)>5L*1024*1024)throw new IllegalArgumentException("Unsafe, missing or oversized source file");
        var lines=Files.readString(file).split("\\R",-1);
        if(last>lines.length)throw new IllegalArgumentException("Source range exceeds captured file");
        return String.join("\n",Arrays.copyOfRange(lines,first-1,last));
    }
}
