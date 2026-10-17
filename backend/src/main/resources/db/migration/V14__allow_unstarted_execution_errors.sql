-- A queued job can expire or be cancelled without ever starting repository code.
-- Preserve started_at=NULL; do not fabricate an execution start time.
ALTER TABLE executions DROP CONSTRAINT chk_execution_times;

ALTER TABLE executions ADD CONSTRAINT chk_execution_times CHECK (
    finished_at IS NULL
    OR (started_at IS NOT NULL AND finished_at >= started_at)
    OR (started_at IS NULL AND execution_state IN ('ERROR', 'CANCELLED')
        AND finished_at >= created_at)
);
