CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    investigation_id UUID NOT NULL REFERENCES investigations(id),
    snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id),
    request_hash VARCHAR(64) NOT NULL,
    state VARCHAR(30) NOT NULL DEFAULT 'READY'
      CHECK(state IN ('READY','RUNNING','MOCK_COMPLETE','REVIEW_REQUIRED','NEEDS_EVIDENCE','BUDGET_EXHAUSTED','FAILED','INTERRUPTED')),
    next_role INTEGER NOT NULL DEFAULT 0 CHECK(next_role BETWEEN 0 AND 5),
    max_calls INTEGER NOT NULL CHECK(max_calls BETWEEN 1 AND 5),
    used_calls INTEGER NOT NULL DEFAULT 0 CHECK(used_calls BETWEEN 0 AND 5),
    max_output_tokens INTEGER NOT NULL CHECK(max_output_tokens BETWEEN 128 AND 20000),
    reserved_output_tokens INTEGER NOT NULL DEFAULT 0 CHECK(reserved_output_tokens>=0),
    deadline TIMESTAMPTZ NOT NULL,
    report JSONB NOT NULL,
    evidence_refs JSONB NOT NULL,
    execution_results JSONB NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX agent_one_active_run ON agent_runs(investigation_id) WHERE state IN ('READY','RUNNING');
CREATE TABLE agent_role_steps (
    run_id UUID NOT NULL REFERENCES agent_runs(id),
    role_number INTEGER NOT NULL CHECK(role_number BETWEEN 0 AND 4),
    role VARCHAR(40) NOT NULL,
    call_id UUID NOT NULL UNIQUE,
    state VARCHAR(30) NOT NULL DEFAULT 'RESERVED',
    output JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY(run_id,role_number)
);
