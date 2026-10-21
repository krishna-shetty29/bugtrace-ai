CREATE TABLE benchmark_reproduction_runs (
    id UUID PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id),
    registration_id UUID NOT NULL REFERENCES benchmark_case_registrations(id),
    snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id),
    content_hash TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    max_calls INTEGER NOT NULL CHECK (max_calls BETWEEN 1 AND 3),
    calls INTEGER NOT NULL DEFAULT 0,
    output_per_call INTEGER NOT NULL,
    reserved_output_tokens INTEGER NOT NULL DEFAULT 0,
    max_output_tokens INTEGER NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    evidence_refs JSONB NOT NULL,
    evidence_ids JSONB NOT NULL,
    query_text TEXT NOT NULL,
    retrieval JSONB NOT NULL,
    report JSONB NOT NULL,
    state TEXT NOT NULL DEFAULT 'READY_ANALYSIS',
    plan JSONB,
    source_document JSONB,
    execution_draft JSONB,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE benchmark_reproduction_steps (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES benchmark_reproduction_runs(id),
    action TEXT NOT NULL,
    call_id UUID,
    state TEXT NOT NULL DEFAULT 'STARTED',
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX benchmark_runs_investigation ON benchmark_reproduction_runs(investigation_id,created_at DESC);
