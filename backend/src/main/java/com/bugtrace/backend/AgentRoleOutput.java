package com.bugtrace.backend;

import java.util.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class AgentRoleOutput {
    public record Output(String summary,List<String> evidenceIds,List<String> missingEvidence,
        String testDraft,String patchDraft,List<String> checks,String experimentType,List<ToolRequest> toolRequests) {}
    public record ToolRequest(String tool,JsonNode arguments) {}
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Output parse(String text,AgentRolePolicy.Role role,Set<String> allowed) {
        if(text==null || text.length()>16384 || text.getBytes(StandardCharsets.UTF_8).length>16384)throw bad("Role output exceeds 16 KiB or is missing");
        JsonNode root;
        try{root=JSON.readTree(text);}catch(Exception ex){throw bad("Role output must be a single JSON object without duplicate keys or trailing data");}
        var keys=List.of("summary","evidenceIds","missingEvidence","testDraft","patchDraft","checks","experimentType");
        if(root==null || !root.isObject() || (root.size()!=keys.size() && root.size()!=keys.size()+1))throw bad("Invalid role output fields");
        for(String key:keys)if(!root.has(key))throw bad("Missing role output field: "+key);
        String summary=string(root.get("summary"),2000,false), test=string(root.get("testDraft"),10000,true), patch=string(root.get("patchDraft"),10000,true);
        List<String> ids=array(root.get("evidenceIds"),8,2500), missing=array(root.get("missingEvidence"),12,1000), checks=array(root.get("checks"),3,40);
        if(!allowed.containsAll(ids))throw bad("Role cited unselected evidence");
        if(ids.isEmpty() && missing.isEmpty())throw bad("Uncited role output must identify missing evidence");
        if(!test.isEmpty() && role!=AgentRolePolicy.Role.REPRODUCTION_AGENT)throw bad("This role cannot generate a test draft");
        if(!patch.isEmpty() && role!=AgentRolePolicy.Role.PATCH_AGENT)throw bad("This role cannot generate a patch draft");
        if(!checks.isEmpty() && role!=AgentRolePolicy.Role.VERIFICATION_AGENT)throw bad("This role cannot select verification checks");
        if(!Set.of("COMPILE","RUN_SELECTED_TEST","RUN_TEST_SUITE").containsAll(checks))throw bad("Unapproved verification operation");
        String experiment=string(root.get("experimentType"),50,false);
        try{HypothesisValidation.ExperimentType.valueOf(experiment);}catch(IllegalArgumentException ex){throw bad("Unapproved experiment type");}
        if(ids.isEmpty() && !experiment.equals("REQUEST_MORE_EVIDENCE"))throw bad("Uncited output must request more evidence");
        var requests=new ArrayList<ToolRequest>();
        if(root.has("toolRequests")) {
            var tools=root.get("toolRequests");
            if(!tools.isArray() || tools.size()>3)throw bad("At most three tool requests are allowed");
            for(var request:tools) {
                RoleToolPolicy.fields(request,"tool","arguments");
                String tool=RoleToolPolicy.text(request,"tool",80);
                if(!request.get("arguments").isObject())throw bad("Tool arguments must be an object");
                requests.add(new ToolRequest(tool,request.get("arguments")));
            }
        }else if(root.size()!=keys.size())throw bad("Unknown role output fields");
        return new Output(summary,ids,missing,test,patch,checks,experiment,List.copyOf(requests));
    }
    private static String string(JsonNode node,int max,boolean empty) {
        if(!node.isString() || node.asString().length()>max || (!empty && node.asString().isBlank()))throw bad("Invalid role text field");
        return node.asString();
    }
    private static List<String> array(JsonNode node,int max,int length) {
        if(!node.isArray() || node.size()>max)throw bad("Invalid role array");
        var list=new ArrayList<String>();for(var item:node)list.add(string(item,length,false));
        if(new HashSet<>(list).size()!=list.size())throw bad("Duplicate role array entry");return List.copyOf(list);
    }
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
