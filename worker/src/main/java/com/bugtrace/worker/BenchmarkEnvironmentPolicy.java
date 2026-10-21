package com.bugtrace.worker;

import java.nio.file.Path;
import java.util.*;

/** Check observed Docker configuration before starting repository code. */
final class BenchmarkEnvironmentPolicy {
    static void validate(Map<?,?> inspect,Path experiment,Path cache,boolean probe) {
        Map<?,?> config=map(inspect.get("Config")),host=map(inspect.get("HostConfig"));
        require(BenchmarkWorkerRegistration.IMAGE.equals(config.get("Image")),"pinned image");
        require("1000:1000".equals(config.get("User")),"non-root user");
        require("none".equals(host.get("NetworkMode")),"network");
        require(number(host.get("NanoCpus"))==1_000_000_000L,"CPU");
        require(number(host.get("Memory"))==1_073_741_824L&&number(host.get("MemorySwap"))==1_073_741_824L,"memory");
        require(number(host.get("PidsLimit"))==128,"process limit");
        require(Boolean.TRUE.equals(host.get("ReadonlyRootfs"))&&Boolean.FALSE.equals(host.get("Privileged")),"root filesystem/privilege");
        require(List.of("ALL").equals(host.get("CapDrop")),"capability drop");
        Object security=host.get("SecurityOpt");
        require(security instanceof List<?> opts&&opts.size()==1&&Set.of("no-new-privileges=true","no-new-privileges:true","no-new-privileges").contains(opts.getFirst()),"security options");
        require(Boolean.TRUE.equals(host.get("Init")),"init");
        require(empty(host.get("Devices"))&&empty(host.get("DeviceRequests"))&&empty(host.get("VolumesFrom"))&&empty(host.get("Links")),"extra host access");
        require(!"host".equals(host.get("PidMode"))&&!"host".equals(host.get("IpcMode")),"host namespaces");
        Map<?,?> tmpfs=map(host.get("Tmpfs"));
        require(tmpfs.keySet().equals(Set.of("/tmp","/home/runner")),"tmpfs paths");
        for(Object options:tmpfs.values())require(String.valueOf(options).contains("nosuid")&&String.valueOf(options).contains("nodev"),"tmpfs restrictions");
        require(inspect.get("Mounts") instanceof List<?>,"mount metadata");
        var mounts=(List<?>)inspect.get("Mounts");
        int bindCount=0;
        Set<String> destinations=new HashSet<>();
        for(Object item:mounts) {
            Map<?,?> mount=map(item);String target=String.valueOf(mount.get("Destination"));
            require(destinations.add(target),"duplicate mount destination");
            if("tmpfs".equals(mount.get("Type"))) {
                require(Set.of("/tmp","/home/runner").contains(target),"tmpfs mount destination");
                continue;
            }
            require(Set.of("/work","/m2").contains(target)&&"bind".equals(mount.get("Type")),"mount type/destination");
            bindCount++;
            Path expected=target.equals("/work")?experiment:cache;
            require(sameDockerSource(String.valueOf(mount.get("Source")),expected),"mount source");
        }
        require(bindCount==(probe?0:2),"bind mount count");
        require(empty(host.get("PortBindings")),"published ports");
        require(config.get("Entrypoint") instanceof List<?>,"entrypoint");
        require((probe?List.of("java"):List.of("/usr/share/maven/bin/mvn")).equals(config.get("Entrypoint")),"entrypoint");
    }
    static Map<String,Object> facts(Map<?,?> inspect) {
        var config=map(inspect.get("Config"));var host=map(inspect.get("HostConfig"));
        var facts=new LinkedHashMap<String,Object>();
        facts.put("image",config.get("Image"));facts.put("user",config.get("User"));
        for(String key:List.of("NetworkMode","NanoCpus","Memory","MemorySwap","PidsLimit","ReadonlyRootfs","Privileged","CapDrop","SecurityOpt","Init"))facts.put(key,host.get(key));
        facts.put("mounts",inspect.get("Mounts"));
        return facts;
    }
    // Docker Desktop reports Windows bind sources through its Linux VM path.
    static boolean sameDockerSource(String actual,Path expected) {
        String path=expected.toAbsolutePath().normalize().toString().replace('\\','/');
        if(path.matches("^[A-Za-z]:/.*")) {
            String tail=Character.toLowerCase(path.charAt(0))+path.substring(2);
            String reported=actual.matches("^[A-Za-z]:[\\\\/].*")?actual.replace('\\','/'):actual;
            return reported.equalsIgnoreCase(path)||actual.equalsIgnoreCase("/run/desktop/mnt/host/"+tail)
                ||actual.equalsIgnoreCase("/host_mnt/"+tail);
        }
        return actual.equals(path);
    }
    static boolean artifactFile(String name) {
        return name.endsWith(".jar")||name.endsWith(".pom")||name.endsWith(".sha1")||name.endsWith(".sha256")
            ||name.endsWith(".md5")||name.endsWith(".lastUpdated")||name.equals("_remote.repositories")
            ||name.equals("resolver-status.properties")||name.matches("maven-metadata[^/\\\\]*\\.xml");
    }
    static void requireJava21(String output) {
        require(output.lines().anyMatch(line->line.strip().equals("java.specification.version = 21")),"container Java 21 probe");
    }
    private static long number(Object value){return value instanceof Number n?n.longValue():Long.MIN_VALUE;}
    private static Map<?,?> map(Object value){if(value instanceof Map<?,?> m)return m;throw new IllegalArgumentException("Missing observed container configuration");}
    private static boolean empty(Object value){return value==null||value instanceof Collection<?> c&&c.isEmpty()||value instanceof Map<?,?> m&&m.isEmpty();}
    private static void require(boolean condition,String field){if(!condition)throw new IllegalArgumentException("Benchmark environment rejected: "+field);}
}
