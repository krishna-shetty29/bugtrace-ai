CREATE TABLE context_embedding_cache (
    model_digest text NOT NULL,
    input_hash text NOT NULL,
    embedding vector(768) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(model_digest,input_hash)
);

CREATE TABLE context_indexes (
    id uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    content_hash text NOT NULL,
    model_digest text NOT NULL,
    algorithm_version text NOT NULL,
    state text NOT NULL CHECK(state IN ('BUILDING','COMPLETE','FAILED')),
    manifest jsonb NOT NULL DEFAULT '[]',
    statistics jsonb NOT NULL DEFAULT '{}',
    technical_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
CREATE INDEX context_indexes_owner ON context_indexes(investigation_id,created_at DESC);

CREATE TABLE context_chunks (
    index_id uuid NOT NULL REFERENCES context_indexes(id) ON DELETE CASCADE,
    chunk_number integer NOT NULL,
    origin text NOT NULL CHECK(origin IN ('SNAPSHOT_DOCUMENT','SNAPSHOT_CONFIG','HISTORICAL_COMMIT','HISTORICAL_DIFF','PREVIOUS_REPORT','EXECUTION_STDOUT','EXECUTION_STDERR','EXECUTION_RESULT')),
    source_ref text NOT NULL,
    source_revision text NOT NULL,
    line_kind text NOT NULL,
    start_line integer NOT NULL CHECK(start_line>0),
    end_line integer NOT NULL CHECK(end_line>=start_line),
    document_hash text NOT NULL,
    chunk_hash text NOT NULL,
    title text NOT NULL,
    source_text text NOT NULL,
    embedding vector(768) NOT NULL,
    PRIMARY KEY(index_id,chunk_number)
);
