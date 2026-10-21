package com.bugtrace.worker;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LocalDockerPolicyTest {
 @Test void rejectsRemoteAndTcpTransports(){for(String value:List.of("tcp://127.0.0.1:2375","tcp://remote:2376","ssh://remote","https://remote","npipe:////remote/pipe/docker_engine"))assertThrows(IllegalArgumentException.class,()->LocalDocker.endpoint(value));}
 @Test void pinsEveryOperationToLocalTransport(){for(String operation:List.of("create","start","inspect","rm","info")){var command=LocalDocker.arguments(List.of("docker",operation,"value"),"npipe:////./pipe/docker_engine");assertEquals(List.of("docker","--host","npipe:////./pipe/docker_engine",operation,"value"),command);}}
 @Test void ignoresAmbientRemoteContextAndRejectsShellCommands(){var process=LocalDocker.process("docker","info");for(String key:List.of("DOCKER_HOST","DOCKER_CONTEXT","DOCKER_TLS_VERIFY","DOCKER_CERT_PATH"))assertFalse(process.environment().containsKey(key));assertThrows(IllegalArgumentException.class,()->LocalDocker.arguments(List.of("powershell","-Command","anything"),null));assertThrows(IllegalArgumentException.class,()->LocalDocker.arguments(List.of("docker","run"),null));}
}
