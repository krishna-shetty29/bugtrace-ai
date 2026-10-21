CREATE TABLE benchmark_case_registrations (
    id UUID PRIMARY KEY,
    case_id VARCHAR(16) NOT NULL CHECK (case_id ~ '^case-00[1-6]$'),
    collection_version VARCHAR(32) NOT NULL,
    release_commit VARCHAR(64) NOT NULL,
    repository TEXT NOT NULL,
    module_path TEXT NOT NULL,
    snapshot_content_hash VARCHAR(64) NOT NULL,
    registration JSONB NOT NULL,
    registration_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(repository,release_commit,case_id)
);
