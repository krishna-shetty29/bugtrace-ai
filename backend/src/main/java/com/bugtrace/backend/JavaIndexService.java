package com.bugtrace.backend;

import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JavaIndexService {
    private final JdbcTemplate jdbc;
    private final SnapshotImportService snapshots;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public JavaIndexService(JdbcTemplate jdbc,SnapshotImportService snapshots) { this.jdbc=jdbc;this.snapshots=snapshots; }
    public Map<String,Object> create(UUID investigation) {
        var verified=snapshots.verifySnapshot(investigation);
        var row=snapshot(investigation);
        try {
            var index=new JavaSymbolIndexer().index(Path.of(row.get("source_location").toString()));
            var after=snapshots.verifySnapshot(investigation);
            if (!Objects.equals(verified.get("contentHash"),after.get("contentHash")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Snapshot changed while indexing");
            String document=mapper.writeValueAsString(index);
            if (document.length()>16*1024*1024) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Index exceeds 16 MiB; use a smaller module");
            jdbc.update("""
                INSERT INTO java_symbol_indexes(snapshot_id,content_hash,parser_version,document,indexed_at)
                VALUES (?,?,?,CAST(? AS jsonb),clock_timestamp())
                ON CONFLICT (snapshot_id) DO UPDATE SET content_hash=EXCLUDED.content_hash,
                    parser_version=EXCLUDED.parser_version,document=EXCLUDED.document,indexed_at=EXCLUDED.indexed_at
                """,row.get("id"),verified.get("contentHash"),JavaSymbolIndexer.VERSION,document);
            return get(investigation);
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Indexing failed: "+exception.getMessage(),exception); }
    }
    public Map<String,Object> get(UUID investigation) {
        var verified=snapshots.verifySnapshot(investigation);
        var rows=jdbc.queryForList("""
            SELECT s.snapshot_id,s.content_hash,s.document::text AS document FROM investigations i
            JOIN java_symbol_indexes s ON s.snapshot_id=i.snapshot_id WHERE i.id=?
            """,investigation);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No saved Java index for this investigation; build it first");
        var row=rows.getFirst();
        if (!Objects.equals(verified.get("contentHash"),row.get("content_hash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Saved index does not match the verified snapshot");
        try { return Map.of("snapshotId",row.get("snapshot_id"),"contentHash",row.get("content_hash"),"index",mapper.readValue(row.get("document").toString(),Map.class)); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Saved index could not be read",exception); }
    }
    private Map<String,Object> snapshot(UUID id) {
        var rows=jdbc.queryForList("""
            SELECT s.id,s.source_location FROM investigations i
            JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?
            """,id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");
        return rows.getFirst();
    }
}
