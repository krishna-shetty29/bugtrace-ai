ALTER TABLE benchmark_reproduction_runs ADD COLUMN retrieval_requests INTEGER NOT NULL DEFAULT 0 CHECK (retrieval_requests BETWEEN 0 AND 30);
CREATE TABLE benchmark_source_versions (
    run_id UUID NOT NULL REFERENCES benchmark_reproduction_runs(id),
    version INTEGER NOT NULL CHECK (version > 0),
    model_call_id UUID NOT NULL REFERENCES ai_model_calls(id),
    source_sha256 TEXT NOT NULL,
    source_document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, version)
);
CREATE TABLE benchmark_case_records (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES benchmark_reproduction_runs(id),
    investigation_id UUID NOT NULL REFERENCES investigations(id),
    snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id),
    content_hash TEXT NOT NULL,
    collection_version TEXT NOT NULL,
    case_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('EXPORT','RETRIEVAL')),
    document JSONB NOT NULL,
    document_sha256 TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id,kind,document_sha256)
);
CREATE INDEX benchmark_case_records_run ON benchmark_case_records(run_id,created_at DESC);
