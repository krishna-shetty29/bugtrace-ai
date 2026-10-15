CREATE TABLE execution_jobs (
    id UUID PRIMARY KEY REFERENCES executions(id),
    snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id),
    operation VARCHAR(30) NOT NULL CHECK (operation IN ('COMPILE','RUN_SELECTED_TEST','RUN_TEST_SUITE')),
    test_selector VARCHAR(200),
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 600),
    queue_state VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (queue_state IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    worker_id VARCHAR(64),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempt_execution_id UUID,
    progress JSONB NOT NULL DEFAULT '{}'::jsonb,
    result JSONB,
    stdout TEXT,
    stderr TEXT,
    technical_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK ((operation = 'RUN_SELECTED_TEST' AND test_selector IS NOT NULL) OR
           (operation <> 'RUN_SELECTED_TEST' AND test_selector IS NULL)),
    CHECK (queue_state <> 'RUNNING' OR (worker_id IS NOT NULL AND lease_token IS NOT NULL
        AND lease_until IS NOT NULL AND attempt_execution_id IS NOT NULL))
);
CREATE INDEX idx_execution_jobs_claim ON execution_jobs(snapshot_id, created_at) WHERE queue_state = 'QUEUED';
CREATE INDEX idx_execution_jobs_expiry ON execution_jobs(lease_until) WHERE queue_state = 'RUNNING';

CREATE TABLE execution_job_attempts (
    attempt_execution_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES execution_jobs(id),
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    lease_token UUID NOT NULL UNIQUE,
    attempt_state VARCHAR(20) NOT NULL CHECK (attempt_state IN ('RUNNING','COMPLETED','EXPIRED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    result JSONB,
    stdout TEXT,
    stderr TEXT,
    technical_error TEXT,
    UNIQUE(job_id, attempt_number)
);
