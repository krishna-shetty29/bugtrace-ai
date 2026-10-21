ALTER TABLE benchmark_reproduction_runs DROP CONSTRAINT benchmark_reproduction_runs_max_calls_check;
ALTER TABLE benchmark_reproduction_runs ADD CHECK (max_calls BETWEEN 1 AND 8);
ALTER TABLE benchmark_reproduction_runs ADD COLUMN experiment_jobs INTEGER NOT NULL DEFAULT 0 CHECK (experiment_jobs BETWEEN 0 AND 3);
ALTER TABLE benchmark_reproduction_runs ADD COLUMN compiler_repairs INTEGER NOT NULL DEFAULT 0 CHECK (compiler_repairs BETWEEN 0 AND 2);
ALTER TABLE execution_jobs ADD COLUMN benchmark_binding JSONB;
CREATE TABLE benchmark_execution_links (
    request_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES benchmark_reproduction_runs(id),
    execution_id UUID NOT NULL UNIQUE REFERENCES execution_jobs(id),
    source_version INTEGER NOT NULL,
    binding_sha256 TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id,source_version) REFERENCES benchmark_source_versions(run_id,version),
    UNIQUE (run_id,source_version)
);
CREATE INDEX benchmark_execution_links_run ON benchmark_execution_links(run_id,created_at);
