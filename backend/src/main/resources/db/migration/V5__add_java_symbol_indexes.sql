CREATE TABLE java_symbol_indexes (
    snapshot_id UUID PRIMARY KEY REFERENCES repository_snapshots(id),
    content_hash VARCHAR(64) NOT NULL,
    parser_version VARCHAR(40) NOT NULL,
    document JSONB NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
