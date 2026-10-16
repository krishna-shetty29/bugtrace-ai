CREATE TABLE java_relationship_graphs (
    snapshot_id UUID PRIMARY KEY REFERENCES repository_snapshots(id),
    content_hash VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(40) NOT NULL,
    document JSONB NOT NULL,
    built_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
