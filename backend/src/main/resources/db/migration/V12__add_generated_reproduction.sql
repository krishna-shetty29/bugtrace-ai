ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_max_calls_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_max_calls_check CHECK(max_calls BETWEEN 1 AND 8);
ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_used_calls_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_used_calls_check CHECK(used_calls BETWEEN 0 AND 8);
ALTER TABLE execution_jobs ADD COLUMN generated_test JSONB;
CREATE TABLE reproduction_runs (
    id UUID PRIMARY KEY,
    agent_run_id UUID NOT NULL UNIQUE REFERENCES agent_runs(id),
    state VARCHAR(40) NOT NULL,
    max_repairs INTEGER NOT NULL CHECK(max_repairs BETWEEN 0 AND 2),
    attempts_count INTEGER NOT NULL DEFAULT 0 CHECK(attempts_count BETWEEN 0 AND 3),
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE reproduction_attempts (
    id UUID PRIMARY KEY,
    reproduction_id UUID NOT NULL REFERENCES reproduction_runs(id),
    attempt_number INTEGER NOT NULL CHECK(attempt_number BETWEEN 0 AND 2),
    call_id UUID NOT NULL UNIQUE,
    document JSONB,
    source_sha VARCHAR(64),
    execution_id UUID REFERENCES execution_jobs(id),
    state VARCHAR(40) NOT NULL,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(reproduction_id,attempt_number)
);
