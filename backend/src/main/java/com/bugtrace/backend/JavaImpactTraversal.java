package com.bugtrace.backend;

import java.util.*;

public final class JavaImpactTraversal {
    private JavaImpactTraversal() {}
    public record Reached(String nodeId,int distance,boolean uncertain,List<JavaRelationshipAnalyzer.Edge> path) {}
    public record Impact(String root,int maxDepth,boolean includeUncertain,boolean truncated,List<Reached> reached) {}
    public static Impact incoming(JavaRelationshipAnalyzer.Graph graph,String root,int depth,boolean includeUncertain) {
        if (depth<1 || depth>5) throw new IllegalArgumentException("Impact depth must be 1 through 5");
        if (graph.nodes().stream().noneMatch(node->node.id().equals(root))) throw new IllegalArgumentException("Select an existing graph node");
        Map<String,List<JavaRelationshipAnalyzer.Edge>> incoming=new HashMap<>();
        for (var edge:graph.edges()) {
            if (!includeUncertain && !edge.confidence().equals("DECLARED")) continue;
            incoming.computeIfAbsent(edge.to(),key->new ArrayList<>()).add(edge);
        }
        Set<String> seen=new HashSet<>();seen.add(root);
        ArrayDeque<Reached> queue=new ArrayDeque<>();queue.add(new Reached(root,0,false,List.of()));
        List<Reached> result=new ArrayList<>();boolean truncated=false;
        while (!queue.isEmpty()) {
            var current=queue.removeFirst();if (current.distance>=depth) continue;
            for (var edge:incoming.getOrDefault(current.nodeId,List.of())) {
                if (seen.contains(edge.from())) continue;
                if (result.size()>=500) { truncated=true;continue; }
                seen.add(edge.from());List<JavaRelationshipAnalyzer.Edge> path=new ArrayList<>(current.path);path.add(edge);
                var next=new Reached(edge.from(),current.distance+1,current.uncertain||!edge.confidence().equals("DECLARED"),List.copyOf(path));
                result.add(next);queue.addLast(next);
            }
        }
        return new Impact(root,depth,includeUncertain,truncated,List.copyOf(result));
    }
}
