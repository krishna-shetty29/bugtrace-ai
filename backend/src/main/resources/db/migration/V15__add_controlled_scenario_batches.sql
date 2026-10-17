ALTER TABLE investigation_loops ADD COLUMN scenario varchar(50);
ALTER TABLE investigation_loops ADD COLUMN repetitions integer NOT NULL DEFAULT 0;
ALTER TABLE investigation_loops ADD CONSTRAINT chk_loop_scenario_batch CHECK (
    (scenario IS NULL AND repetitions=0)
    OR (scenario IN ('RETRY_AFTER_RESPONSE_LOSS','DELAYED_RESPONSE','CONCURRENT_RETRIES','RETRY_AFTER_COMPLETION')
        AND repetitions BETWEEN 2 AND 30)
);
ALTER TABLE investigation_loop_rounds ADD COLUMN scenario_report jsonb;
