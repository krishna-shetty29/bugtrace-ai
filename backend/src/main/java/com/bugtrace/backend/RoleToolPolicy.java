package com.bugtrace.backend;

import java.util.*;
import tools.jackson.databind.JsonNode;

public final class RoleToolPolicy {
    private static final Map<AgentRolePolicy.Role,Set<String>> TOOLS=Map.of(
        AgentRolePolicy.Role.EVIDENCE_ANALYST,Set.of("searchCode","readFile","readEvidence"),
        AgentRolePolicy.Role.REPRODUCTION_AGENT,Set.of("createTest","requestExperiment","readExecutionResults"),
        AgentRolePolicy.Role.DIAGNOSIS_AGENT,Set.of("readEvidence","readExecutionResults","readTestReports"),
        AgentRolePolicy.Role.PATCH_AGENT,Set.of("readDiagnosis","readRelevantFiles","proposePatch"),
        AgentRolePolicy.Role.VERIFICATION_AGENT,Set.of("requestApprovedChecks","readTestReports","createVerificationSummary"));
    public static Set<String> tools(AgentRolePolicy.Role role){return TOOLS.get(role);}
    public static void authorize(AgentRolePolicy.Role role,String tool){
        if(!tools(role).contains(tool))throw new IllegalArgumentException("Tool is not allowed for the current server-selected role");
    }
    public static void fields(JsonNode args,String... fields) {
        if(args==null || !args.isObject() || args.size()!=fields.length)throw new IllegalArgumentException("Unexpected tool argument fields");
        for(String field:fields)if(!args.has(field))throw new IllegalArgumentException("Missing tool argument field: "+field);
    }
    public static String text(JsonNode args,String name,int max) {
        var value=args.get(name);
        if(value==null || !value.isString() || value.asString().isBlank() || value.asString().length()>max)throw new IllegalArgumentException("Invalid tool text field: "+name);
        return value.asString();
    }
    public static int number(JsonNode args,String name,int min,int max) {
        var value=args.get(name);
        if(value==null || !value.isInt() || value.asInt()<min || value.asInt()>max)throw new IllegalArgumentException("Invalid tool integer field: "+name);
        return value.asInt();
    }
    public static boolean safePath(String path) {
        if(path==null || path.isBlank() || path.length()>1000 || path.startsWith("/") || path.contains("\\") || path.contains(":")
            || path.contains("%") || path.contains("?") || path.contains("#") || path.chars().anyMatch(Character::isISOControl))return false;
        for(String part:path.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))return false;
        return true;
    }
    public static boolean testPath(String path) {
        return safePath(path) && path.startsWith("src/test/java/") && path.endsWith(".java");
    }
    public static boolean selectedTest(String selector,Set<String> selectedPaths) {
        if(selector==null || !selector.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))return false;
        for(String path:selectedPaths)if(testPath(path)) {
            String qualified=path.substring("src/test/java/".length(),path.length()-5).replace('/','.');
            if(selector.equals(qualified) || selector.equals(qualified.substring(qualified.lastIndexOf('.')+1)))return true;
        }
        return false;
    }
    /** Header validation only; this does not prove the diff applies. */
    public static void patchHeaders(String diff,Set<String> selectedPaths) {
        String old=null;int pairs=0;
        for(String line:diff.split("\n")) {
            if(line.startsWith("diff --git ") || line.startsWith("index ") || line.startsWith("@@") || line.startsWith("\\ No newline"))continue;
            if(line.startsWith("--- ")) {
                if(old!=null || !line.startsWith("--- a/"))throw new IllegalArgumentException("Use paired existing-source unified diff headers");
                old=line.substring(6).strip();
                if(!safePath(old) || !selectedPaths.contains(old))throw new IllegalArgumentException("Patch path is outside selected current files");
            }else if(line.startsWith("+++ ")) {
                if(old==null || !line.equals("+++ b/"+old))throw new IllegalArgumentException("Patch header paths must match selected files");
                old=null;pairs++;
            }else if(!line.isEmpty() && !line.startsWith("+") && !line.startsWith("-") && !line.startsWith(" "))throw new IllegalArgumentException("Unsupported patch format");
        }
        if(old!=null || pairs==0)throw new IllegalArgumentException("Patch needs selected-file unified diff headers");
    }
}
