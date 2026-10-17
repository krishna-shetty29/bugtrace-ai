ALTER TABLE execution_jobs ADD COLUMN not_after timestamptz;

CREATE TABLE investigation_loops (
    id uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    content_hash varchar(64) NOT NULL,
    report jsonb NOT NULL,
    evidence_refs jsonb NOT NULL,
    evidence_ids jsonb NOT NULL,
    query_text varchar(200) NOT NULL,
    limits jsonb NOT NULL,
    output_per_call integer NOT NULL CHECK(output_per_call>0),
    version integer NOT NULL DEFAULT 0,
    state varchar(40) NOT NULL DEFAULT 'READY_HYPOTHESIS',
    round_number integer NOT NULL DEFAULT 1,
    experiments integer NOT NULL DEFAULT 0 CHECK(experiments BETWEEN 0 AND 3),
    jobs integer NOT NULL DEFAULT 0 CHECK(jobs BETWEEN 0 AND 6),
    calls integer NOT NULL DEFAULT 0 CHECK(calls BETWEEN 0 AND 12),
    reserved_output_tokens integer NOT NULL DEFAULT 0 CHECK(reserved_output_tokens>=0),
    reserved_execution_seconds integer NOT NULL DEFAULT 0 CHECK(reserved_execution_seconds>=0),
    execution_enabled boolean NOT NULL DEFAULT false,
    message text,
    deadline timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX investigation_loops_one_active ON investigation_loops(investigation_id)
WHERE state IN ('READY_HYPOTHESIS','BUSY','PLAN_READY','PLAN_VALIDATED','DRAFT_READY','WAITING','RESULT_READY','COMPILE_ERROR');
CREATE TABLE investigation_loop_rounds (
    loop_id uuid NOT NULL REFERENCES investigation_loops(id),
    number integer NOT NULL,
    plan jsonb,
    retrieval jsonb,
    source_document jsonb,
    source_sha varchar(64),
    repair_number integer NOT NULL DEFAULT 0 CHECK(repair_number BETWEEN 0 AND 2),
    execution_id uuid REFERENCES execution_jobs(id),
    outcome varchar(40),
    actual_result jsonb,
    review jsonb,
    PRIMARY KEY(loop_id,number)
);
CREATE TABLE investigation_loop_steps (
    id uuid PRIMARY KEY,
    loop_id uuid NOT NULL REFERENCES investigation_loops(id),
    round_number integer NOT NULL,
    action varchar(40) NOT NULL,
    request_hash varchar(64) NOT NULL,
    state varchar(40) NOT NULL,
    model_call_id uuid,
    execution_id uuid REFERENCES execution_jobs(id),
    document jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
