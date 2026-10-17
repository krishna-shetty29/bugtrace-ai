ALTER TABLE agent_runs ADD COLUMN used_tool_calls INTEGER NOT NULL DEFAULT 0 CHECK(used_tool_calls BETWEEN 0 AND 30);
ALTER TABLE agent_runs ADD COLUMN execution_tools_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_state_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_state_check CHECK(state IN
 ('READY','RUNNING','TOOL_REVIEW','MOCK_COMPLETE','REVIEW_REQUIRED','NEEDS_EVIDENCE','BUDGET_EXHAUSTED','FAILED','INTERRUPTED'));
DROP INDEX agent_one_active_run;
CREATE UNIQUE INDEX agent_one_active_run ON agent_runs(investigation_id) WHERE state IN ('READY','RUNNING','TOOL_REVIEW');
CREATE TABLE role_tool_calls (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id),
    role_number INTEGER NOT NULL,
    role VARCHAR(40) NOT NULL,
    tool VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    arguments JSONB NOT NULL,
    state VARCHAR(20) NOT NULL CHECK(state IN ('STARTED','SUCCEEDED','DENIED','FAILED')),
    result JSONB,
    execution_id UUID REFERENCES execution_jobs(id),
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
CREATE INDEX role_tool_run_calls ON role_tool_calls(run_id,created_at);
