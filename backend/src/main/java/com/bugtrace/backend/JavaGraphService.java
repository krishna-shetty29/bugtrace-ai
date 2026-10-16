package com.bugtrace.backend;

import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JavaGraphService {
    private final JdbcTemplate jdbc;
    private final SnapshotImportService snapshots;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public JavaGraphService(JdbcTemplate jdbc,SnapshotImportService snapshots) { this.jdbc=jdbc;this.snapshots=snapshots; }
    public Map<String,Object> create(UUID investigation) {
        var verified=snapshots.verifySnapshot(investigation);
        var rows=jdbc.queryForList("SELECT s.id,s.source_location FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");
        var row=rows.getFirst();
        try {
            var graph=new JavaRelationshipAnalyzer().analyze(Path.of(row.get("source_location").toString()));
            var after=snapshots.verifySnapshot(investigation);
            if (!Objects.equals(verified.get("contentHash"),after.get("contentHash")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Snapshot changed during analysis");
            String document=mapper.writeValueAsString(graph);
            if (document.length()>32*1024*1024) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Graph exceeds 32 MiB character limit");
            jdbc.update("""
                INSERT INTO java_relationship_graphs(snapshot_id,content_hash,algorithm_version,document,built_at)
                VALUES (?,?,?,CAST(? AS jsonb),clock_timestamp())
                ON CONFLICT (snapshot_id) DO UPDATE SET content_hash=EXCLUDED.content_hash,
                    algorithm_version=EXCLUDED.algorithm_version,document=EXCLUDED.document,built_at=EXCLUDED.built_at
                """,row.get("id"),verified.get("contentHash"),JavaRelationshipAnalyzer.VERSION,document);
            return get(investigation);
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Relationship analysis failed: "+exception.getMessage(),exception); }
    }
    public Map<String,Object> get(UUID investigation) {
        var verified=snapshots.verifySnapshot(investigation);
        var rows=jdbc.queryForList("""
            SELECT g.snapshot_id,g.content_hash,g.document::text AS document FROM investigations i
            JOIN java_relationship_graphs g ON g.snapshot_id=i.snapshot_id WHERE i.id=?
            """,investigation);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No saved relationship graph; build it first");
        var row=rows.getFirst();
        if (!Objects.equals(verified.get("contentHash"),row.get("content_hash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Saved graph does not match the verified snapshot");
        try { return Map.of("snapshotId",row.get("snapshot_id"),"contentHash",row.get("content_hash"),"graph",mapper.readValue(row.get("document").toString(),Map.class)); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Saved graph could not be read",exception); }
    }
    public JavaImpactTraversal.Impact impact(UUID investigation,String nodeId,int depth,boolean includeUncertain) {
        try {
            var graph=mapper.convertValue(get(investigation).get("graph"),JavaRelationshipAnalyzer.Graph.class);
            return JavaImpactTraversal.incoming(graph,nodeId,depth,includeUncertain);
        } catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,exception.getMessage()); }
    }
}
