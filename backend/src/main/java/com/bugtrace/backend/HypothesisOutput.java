package com.bugtrace.backend;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Syntax and citation checks establish structure, not factual correctness. */
public final class HypothesisOutput {
    private static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Hypothesis(String hypothesis, List<String> evidenceIds,
        String suggestedExperiment, List<String> missingEvidence) {}

    public static Hypothesis parse(String text, Set<String> allowedIds) {
        if (text == null || text.length() > 32000) throw new IllegalArgumentException("Missing or oversized JSON");
        JsonNode root;
        try { root = JSON.readTree(text); }
        catch (Exception ex) { throw new IllegalArgumentException("Response must contain one JSON object without Markdown"); }
        if (root == null || !root.isObject() || root.size() != 4
            || !root.has("hypothesis") || !root.has("evidenceIds")
            || !root.has("suggestedExperiment") || !root.has("missingEvidence"))
            throw new IllegalArgumentException("Response must contain exactly the four hypothesis fields");
        String hypothesis = string(root.get("hypothesis"), 2000);
        String experiment = string(root.get("suggestedExperiment"), 2000);
        List<String> ids = strings(root.get("evidenceIds"), 8, 2500);
        List<String> missing = strings(root.get("missingEvidence"), 12, 1000);
        if (new HashSet<>(ids).size() != ids.size() || !allowedIds.containsAll(ids))
            throw new IllegalArgumentException("Citations must be unique IDs from the selected evidence");
        if (ids.isEmpty() && missing.isEmpty())
            throw new IllegalArgumentException("An uncited hypothesis must identify missing evidence");
        return new Hypothesis(hypothesis, ids, experiment, missing);
    }
    private static String string(JsonNode node, int max) {
        if (node == null || !node.isString() || node.asString().isBlank() || node.asString().length() > max)
            throw new IllegalArgumentException("Invalid or oversized text field");
        return node.asString();
    }
    private static List<String> strings(JsonNode node, int max, int length) {
        if (node == null || !node.isArray() || node.size() > max)
            throw new IllegalArgumentException("Invalid or oversized array field");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(string(item, length));
        return List.copyOf(result);
    }
}
