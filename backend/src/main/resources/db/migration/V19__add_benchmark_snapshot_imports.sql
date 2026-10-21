CREATE TABLE benchmark_snapshot_imports (
    investigation_id UUID PRIMARY KEY REFERENCES investigations(id),
    snapshot_id UUID NOT NULL UNIQUE REFERENCES repository_snapshots(id),
    registration_id UUID NOT NULL REFERENCES benchmark_case_registrations(id),
    case_id VARCHAR(16) NOT NULL,
    release_commit VARCHAR(64) NOT NULL,
    benchmark_content_hash VARCHAR(64) NOT NULL CHECK (benchmark_content_hash ~ '^[0-9a-f]{64}$'),
    platform_content_hash VARCHAR(64) NOT NULL CHECK (platform_content_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
