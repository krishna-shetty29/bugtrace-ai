package com.bugtrace.backend;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;

public final class RoleToolFiles {
    public static String read(Path base,String relative,int start,int end) throws IOException {
        if(!RoleToolPolicy.safePath(relative) || start<1 || end<start || end-start>=200)throw new IllegalArgumentException("Use a relative path and at most 200 source lines");
        Path root=base.toAbsolutePath().normalize(),file=root.resolve(relative).normalize();
        if(!file.startsWith(root) || Files.isSymbolicLink(root))throw new IllegalArgumentException("Invalid base or file path");
        Path walk=root;
        for(Path part:root.relativize(file)) {walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new IllegalArgumentException("Symlink reads are not allowed");}
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root.toRealPath()) || Files.size(file)>1048576)
            throw new IllegalArgumentException("File is outside the captured base or exceeds 1 MiB");
        var output=new StringBuilder();
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            for(int line=1;line<=end;line++) {
                String text=reader.readLine();if(text==null)throw new IllegalArgumentException("Line range exceeds file");
                if(line>=start){if(!output.isEmpty())output.append('\n');output.append(text);}
                if(output.length()>16000)throw new IllegalArgumentException("Source excerpt exceeds 16000 characters");
            }
        }
        return output.toString();
    }
}
