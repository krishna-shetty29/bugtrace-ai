package com.bugtrace.worker;
import java.util.*;
/** Laptop-local transport selected by operator environment, never job input. */
final class LocalDocker {
 private LocalDocker() {}
 static String endpoint(String supplied) {
  String value=supplied==null||supplied.isBlank() ? (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "npipe:////./pipe/docker_engine" : "unix:///var/run/docker.sock") : supplied;
  if(!Set.of("npipe:////./pipe/docker_engine","npipe:////./pipe/dockerDesktopLinuxEngine","unix:///var/run/docker.sock").contains(value))throw new IllegalArgumentException("Worker Docker transport must use an approved local socket or named pipe");
  return value;
 }
 static List<String> arguments(List<String> args,String supplied) {
  if(args.size()<2||!args.getFirst().equals("docker")||!Set.of("create","start","inspect","rm","info").contains(args.get(1)))throw new IllegalArgumentException("Unsupported Docker operation");
  var result=new ArrayList<String>();result.add("docker");result.add("--host");result.add(endpoint(supplied));result.addAll(args.subList(1,args.size()));return List.copyOf(result);
 }
 static ProcessBuilder process(String... args){return process(List.of(args));}
 static ProcessBuilder process(List<String> args){
  var builder=new ProcessBuilder(arguments(args,System.getenv("WORKER_DOCKER_ENDPOINT")));
  for(String name:List.of("DOCKER_CONTEXT","DOCKER_HOST","DOCKER_TLS_VERIFY","DOCKER_CERT_PATH"))builder.environment().remove(name);
  return builder;
 }
}
