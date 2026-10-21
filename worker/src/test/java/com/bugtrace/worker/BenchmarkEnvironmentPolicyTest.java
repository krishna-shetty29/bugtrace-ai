package com.bugtrace.worker;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkEnvironmentPolicyTest {
    final Path source=Path.of("experiment"),cache=Path.of("cache");
    Map<String,Object> config(boolean probe) {
        return new HashMap<>(Map.of("Image",BenchmarkWorkerRegistration.IMAGE,"User","1000:1000","Entrypoint",probe?List.of("java"):List.of("/usr/share/maven/bin/mvn")));
    }
    Map<String,Object> host() {
        var m=new HashMap<String,Object>();m.put("NetworkMode","none");m.put("NanoCpus",1_000_000_000L);
        m.put("Memory",1_073_741_824L);m.put("MemorySwap",1_073_741_824L);m.put("PidsLimit",128);
        m.put("ReadonlyRootfs",true);m.put("Privileged",false);m.put("CapDrop",List.of("ALL"));
        m.put("SecurityOpt",List.of("no-new-privileges=true"));m.put("Init",true);
        m.put("Tmpfs",Map.of("/tmp","rw,nosuid,nodev,size=128m,uid=1000,gid=1000","/home/runner","rw,nosuid,nodev,size=64m,uid=1000,gid=1000"));return m;
    }
    Map<String,Object> inspect(boolean probe) {
        return new HashMap<>(Map.of("Config",config(probe),"HostConfig",host(),"Mounts",probe?List.of():List.of(
            Map.of("Type","bind","Destination","/work","Source",source.toAbsolutePath().normalize().toString().replace('\\','/')),
            Map.of("Type","bind","Destination","/m2","Source",cache.toAbsolutePath().normalize().toString().replace('\\','/')))));
    }
    @Test void acceptsObservedRestrictedMavenContainer(){assertDoesNotThrow(()->BenchmarkEnvironmentPolicy.validate(inspect(false),source,cache,false));}
    @Test void acceptsUnMountedProbe(){assertDoesNotThrow(()->BenchmarkEnvironmentPolicy.validate(inspect(true),source,cache,true));}
    @Test void rejectsWeakenedHostSettings(){for(String key:List.of("NetworkMode","NanoCpus","Memory","MemorySwap","PidsLimit","ReadonlyRootfs","Privileged","CapDrop","SecurityOpt","Init","Tmpfs")){var h=host();h.put(key,"unsafe");var data=inspect(false);data.put("HostConfig",h);assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.validate(data,source,cache,false),key);}}
    @Test void rejectsExtraHostAccess(){for(String key:List.of("Devices","DeviceRequests","VolumesFrom","Links","PortBindings")){var h=host();h.put(key,List.of("extra"));var data=inspect(false);data.put("HostConfig",h);assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.validate(data,source,cache,false),key);}}
    @Test void rejectsImageUserAndEntrypointChanges(){for(String key:List.of("Image","User","Entrypoint")){var c=config(false);c.put(key,"unsafe");var data=inspect(false);data.put("Config",c);assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.validate(data,source,cache,false),key);}}
    @Test void rejectsSocketOrCredentialMount(){var data=inspect(false);data.put("Mounts",List.of(Map.of("Type","bind","Destination","/var/run/docker.sock","Source","/var/run/docker.sock")));assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.validate(data,source,cache,false));}
    @Test void rejectsWrongMountSources(){var data=inspect(false);data.put("Mounts",List.of(Map.of("Type","bind","Destination","/work","Source","/host/secrets"),Map.of("Type","bind","Destination","/m2","Source",cache.toString())));assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.validate(data,source,cache,false));}
    @Test void rejectsRepositoryMountOnProbe(){assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.validate(inspect(false),source,cache,true));}
    @Test void acceptsOnlyDependencyCacheArtifacts(){for(String name:List.of("library.jar","library.pom","library.jar.sha1","_remote.repositories","maven-metadata-local.xml"))assertTrue(BenchmarkEnvironmentPolicy.artifactFile(name),name);for(String name:List.of("settings.xml","settings-security.xml","id_rsa","credentials",".env","token.txt"))assertFalse(BenchmarkEnvironmentPolicy.artifactFile(name),name);}
    @Test void requiresObservedJavaSpecificationVersion(){assertDoesNotThrow(()->BenchmarkEnvironmentPolicy.requireJava21("    java.specification.version = 21\n"));assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.requireJava21("java.specification.version = 17"));assertThrows(Exception.class,()->BenchmarkEnvironmentPolicy.requireJava21("Build Java 21"));}
    @Test void probeCommandHasNoMountsAndCannotRunRepositoryCode(){var args=DockerCommands.environmentProbe("bugtrace-1234",BenchmarkWorkerRegistration.IMAGE);assertFalse(args.contains("--mount"));assertFalse(args.contains("--env-file"));assertFalse(args.contains("--privileged"));assertTrue(args.contains("none"));assertEquals("java",args.get(args.indexOf("--entrypoint")+1));assertEquals(List.of("-XshowSettings:properties","-version"),args.subList(args.indexOf(BenchmarkWorkerRegistration.IMAGE)+1,args.size()));}
    @Test void handlesDockerDesktopMountSourceTranslation(){Path path=Path.of("C:\\Users\\ADMIN\\experiment");if(System.getProperty("os.name").startsWith("Windows")){assertTrue(BenchmarkEnvironmentPolicy.sameDockerSource("/run/desktop/mnt/host/c/Users/ADMIN/experiment",path));assertFalse(BenchmarkEnvironmentPolicy.sameDockerSource("/run/desktop/mnt/host/c/Users/OTHER/experiment",path));}}
    @Test void acceptsDockerInspectIncludingApprovedTmpfsMounts() {
        var data=inspect(true);data.put("Mounts",List.of(Map.of("Type","tmpfs","Destination","/tmp"),Map.of("Type","tmpfs","Destination","/home/runner")));
        assertDoesNotThrow(()->BenchmarkEnvironmentPolicy.validate(data,source,cache,true));
    }
    @Test void acceptsExactWindowsSourceWithBackslashes() {
        if(!System.getProperty("os.name").startsWith("Windows"))return;
        Path expected=Path.of("C:\\Users\\ADMIN\\experiment");
        assertTrue(BenchmarkEnvironmentPolicy.sameDockerSource("C:\\Users\\ADMIN\\experiment",expected));
        assertTrue(BenchmarkEnvironmentPolicy.sameDockerSource("C:/Users/ADMIN/experiment",expected));
    }
    @Test void windowsNormalizationDoesNotBroadenFolderScope() {
        if(!System.getProperty("os.name").startsWith("Windows"))return;
        Path expected=Path.of("C:\\Users\\ADMIN\\experiment");
        for(String wrong:List.of("D:\\Users\\ADMIN\\experiment","C:\\Users\\ADMIN\\experiment-extra","C:\\Users\\ADMIN\\experiment\\..\\secrets","/unknown/c/Users/ADMIN/experiment","/var/run/docker.sock"))
            assertFalse(BenchmarkEnvironmentPolicy.sameDockerSource(wrong,expected),wrong);
    }
}
