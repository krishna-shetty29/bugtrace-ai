package com.bugtrace.backend;

import java.util.*;

/** Reciprocal rank fusion. Raw lexical scores and cosine distances are not comparable. */
public final class HybridRanking {
    public record Candidate(String key,String filePath,int startLine,int endLine,String name,
        String text,String executionId,Double cosineSimilarity) {}
    public record Ranked(Candidate context,double score,Map<String,Integer> channels) {}
    public static List<Ranked> fuse(Map<String,List<Candidate>> lists,int limit){
        Map<String,Candidate> values=new HashMap<>();Map<String,Map<String,Integer>> ranks=new HashMap<>();
        lists.forEach((channel,candidates)->{
            Set<String> seen=new HashSet<>();int rank=0;
            for(var candidate:candidates){
                if(!seen.add(candidate.key()))continue;
                rank++;values.merge(candidate.key(),candidate,(old,next)->next.cosineSimilarity()!=null?next:old);
                ranks.computeIfAbsent(candidate.key(),ignored->new TreeMap<>()).put(channel,rank);
            }
        });
        return values.entrySet().stream().map(entry->{var channels=ranks.get(entry.getKey());
            return new Ranked(entry.getValue(),channels.values().stream().mapToDouble(rank->1.0/(60+rank)).sum(),Map.copyOf(channels));
        }).sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(hit->hit.context().key())).limit(limit).toList();
    }
}
