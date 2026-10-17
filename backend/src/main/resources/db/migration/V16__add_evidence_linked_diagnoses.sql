CREATE TABLE diagnosis_reports (
    id uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    content_hash varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    report_input jsonb NOT NULL,
    evidence_refs jsonb NOT NULL,
    source_pins jsonb NOT NULL,
    execution_pins jsonb NOT NULL,
    state varchar(40) NOT NULL DEFAULT 'GENERATING',
    model_call_id uuid NOT NULL,
    reserved_output_tokens integer NOT NULL CHECK(reserved_output_tokens>0),
    document jsonb,
    document_hash varchar(64),
    source_citations jsonb,
    verification_state varchar(30) NOT NULL DEFAULT 'NOT_CHECKED',
    message text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    verified_at timestamptz
);
CREATE UNIQUE INDEX diagnosis_reports_one_active ON diagnosis_reports(investigation_id) WHERE state='GENERATING';
