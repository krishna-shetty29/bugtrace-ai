CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE semantic_indexes (
    id uuid PRIMARY KEY,
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    content_hash text NOT NULL,
    model_name text NOT NULL,
    model_digest text NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions = 768),
    chunker_version text NOT NULL,
    state text NOT NULL CHECK (state IN ('BUILDING','COMPLETE','PARTIAL','FAILED')),
    chunk_count integer NOT NULL DEFAULT 0,
    diagnostics jsonb NOT NULL DEFAULT '[]',
    technical_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
CREATE INDEX semantic_indexes_snapshot ON semantic_indexes(snapshot_id,created_at DESC);

CREATE TABLE semantic_chunks (
    index_id uuid NOT NULL REFERENCES semantic_indexes(id) ON DELETE CASCADE,
    chunk_key text NOT NULL,
    file_path text NOT NULL,
    start_line integer NOT NULL CHECK (start_line > 0),
    end_line integer NOT NULL CHECK (end_line >= start_line),
    kind text NOT NULL,
    symbol_name text NOT NULL,
    is_test boolean NOT NULL,
    source_text text NOT NULL,
    text_hash text NOT NULL,
    embedding vector(768) NOT NULL,
    PRIMARY KEY(index_id,chunk_key)
);
-- Exact cosine search is sufficient for this bounded fixture index.
-- Add HNSW only after measuring larger collections and filtered-query recall.
