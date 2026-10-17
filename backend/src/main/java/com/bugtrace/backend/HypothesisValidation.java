package com.bugtrace.backend;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates data only. No returned field is executable or grants permissions. */
public final class HypothesisValidation {
    public static final int MAX_BYTES = 16384;
    public static final String VERSION = "hypothesis-validation-v1";
    public enum ExperimentType {
        RETRY_AFTER_RESPONSE_LOSS, CONCURRENT_RETRY, CONFLICTING_CHECKOUT,
        INSPECT_EXISTING_TESTS, REQUEST_MORE_EVIDENCE
    }
    public record Source(String id, String origin, String path, String revision,
        String lineKind, int startLine, int endLine, String hash, String text) {}
    public record FileReference(String evidenceId, String path, int startLine, int endLine) {}
    public record Validated(String hypothesis, List<String> evidenceIds, String suggestedExperiment,
        List<String> missingEvidence, ExperimentType experimentType, List<FileReference> fileReferences) {}
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public static Validated validate(String text, List<Source> evidence) {
        if (text == null || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw bad("Output exceeds 16384 UTF-8 bytes or is missing");
        JsonNode root;
        try { root = JSON.readTree(text); }
        catch (Exception ex) { throw bad("Return one JSON object without duplicate keys, trailing data or Markdown"); }
        if (root == null || !root.isObject() || root.size() != 6)
            throw bad("Exactly six hypothesis fields are required");
        for (String key : List.of("hypothesis","evidenceIds","suggestedExperiment","missingEvidence","experimentType","fileReferences"))
            if (!root.has(key)) throw bad("Missing required field: " + key);
        var sources = new HashMap<String,Source>();
        for (Source item : evidence) sources.put(item.id(),item);
        // Reuse the Step 28 text, array, citation and explicit-gap rules.
        var core = JSON.createObjectNode();
        for (String key : List.of("hypothesis","evidenceIds","suggestedExperiment","missingEvidence")) core.set(key,root.get(key));
        var parsed = HypothesisOutput.parse(JSON.writeValueAsString(core),sources.keySet());
        ExperimentType type;
        try {
            if (!root.get("experimentType").isString()) throw bad("Experiment type must be a string");
            type = ExperimentType.valueOf(root.get("experimentType").asString());
        } catch (IllegalArgumentException ex) { throw bad("Experiment type is not on the platform allowlist"); }
        if (parsed.evidenceIds().isEmpty() && type != ExperimentType.REQUEST_MORE_EVIDENCE)
            throw bad("Uncited output must request more evidence");
        var files = root.get("fileReferences");
        if (!files.isArray() || files.size() > 8) throw bad("File references must be an array of at most eight entries");
        var refs = new ArrayList<FileReference>(); var unique = new HashSet<FileReference>();
        for (JsonNode file : files) {
            if (!file.isObject() || file.size() != 4 || !file.has("evidenceId") || !file.has("path")
                || !file.has("startLine") || !file.has("endLine")
                || !file.get("evidenceId").isString() || !file.get("path").isString()
                || !file.get("startLine").isInt() || !file.get("endLine").isInt())
                throw bad("Invalid file reference fields or line numbers");
            String id = file.get("evidenceId").asString(), path = file.get("path").asString();
            Source source = sources.get(id);
            if (!parsed.evidenceIds().contains(id) || source == null) throw bad("File reference must cite selected evidence");
            if (!Set.of("CURRENT_SNAPSHOT_CODE","SNAPSHOT_DOCUMENT","SNAPSHOT_CONFIG").contains(source.origin())
                || !"SOURCE_LINE".equals(source.lineKind())) throw bad("Historical patches, reports and logs are not current file references");
            if (!safePath(path) || !path.equals(source.path())) throw bad("File path must exactly match the selected snapshot source");
            int start=file.get("startLine").asInt(), end=file.get("endLine").asInt();
            if (start < 1 || end < start || start < source.startLine() || end > source.endLine())
                throw bad("File line range must stay inside the selected source snippet");
            var ref = new FileReference(id,path,start,end);
            if (!unique.add(ref)) throw bad("Duplicate file reference");
            refs.add(ref);
        }
        return new Validated(parsed.hypothesis(),parsed.evidenceIds(),parsed.suggestedExperiment(),
            parsed.missingEvidence(),type,List.copyOf(refs));
    }
    private static boolean safePath(String path) {
        if (path.isBlank() || path.length()>1000 || path.startsWith("/") || path.contains("\\")
            || path.contains(":") || path.contains("%") || path.contains("?") || path.contains("#")
            || path.chars().anyMatch(c -> Character.isISOControl(c))) return false;
        for (String part : path.split("/",-1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
