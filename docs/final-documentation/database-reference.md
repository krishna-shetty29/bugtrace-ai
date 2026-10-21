# Database migration reference
Ordered migration source is authoritative for columns, foreign keys, constraints and indexes. Apply with Flyway; this document is not a SQL script to rerun.

## V1__initialize_database.sql
```sql
-- Verify the initial migration pipeline.
-- Domain tables will be introduced in later migrations.
SELECT 1;
```

## V2__create_initial_tables.sql
```sql
CREATE TABLE projects (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          name VARCHAR(200) NOT NULL,
                          description TEXT,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repository_snapshots (
                                      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                      project_id UUID NOT NULL REFERENCES projects(id),
                                      repository_url TEXT NOT NULL,
                                      commit_sha VARCHAR(64) NOT NULL,
                                      branch_name TEXT,
                                      source_location TEXT,
                                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                      UNIQUE (id, project_id)
);

CREATE TABLE investigations (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                project_id UUID NOT NULL REFERENCES projects(id),
                                snapshot_id UUID NOT NULL,
                                title VARCHAR(300) NOT NULL,
                                bug_description TEXT NOT NULL,
                                expected_behavior TEXT NOT NULL,
                                observed_behavior TEXT NOT NULL,
                                workflow_state VARCHAR(30) NOT NULL DEFAULT 'CREATED',
                                outcome VARCHAR(30),
                                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_investigation_snapshot_project
                                    FOREIGN KEY (snapshot_id, project_id)
                                        REFERENCES repository_snapshots(id, project_id),

                                CONSTRAINT chk_investigation_workflow_state
                                    CHECK (workflow_state IN (
                                                              'CREATED',
                                                              'QUEUED',
                                                              'INDEXING',
                                                              'INVESTIGATING',
                                                              'VERIFYING',
                                                              'REVIEW_READY',
                                                              'COMPLETED',
                                                              'CANCELLED'
                                        )),

                                CONSTRAINT chk_investigation_outcome
                                    CHECK (outcome IN (
                                                       'REPRODUCED',
                                                       'PARTIALLY_REPRODUCED',
                                                       'INCONCLUSIVE',
                                                       'BLOCKED'
                                        ))
);

CREATE TABLE executions (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            investigation_id UUID NOT NULL REFERENCES investigations(id),
                            command TEXT NOT NULL,
                            execution_state VARCHAR(30) NOT NULL DEFAULT 'PENDING',
                            exit_code INTEGER,
                            result_summary TEXT,
                            technical_error_code VARCHAR(100),
                            technical_error_message TEXT,
                            started_at TIMESTAMPTZ,
                            finished_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            UNIQUE (id, investigation_id),

                            CONSTRAINT chk_execution_state
                                CHECK (execution_state IN (
                                                           'PENDING',
                                                           'RUNNING',
                                                           'FINISHED',
                                                           'ERROR',
                                                           'CANCELLED'
                                    )),

                            CONSTRAINT chk_execution_times
                                CHECK (
                                    finished_at IS NULL
                                        OR (
                                        started_at IS NOT NULL
                                            AND finished_at >= started_at
                                        )
                                    )
);

CREATE TABLE artifacts (
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           investigation_id UUID NOT NULL REFERENCES investigations(id),
                           execution_id UUID,
                           artifact_type VARCHAR(50) NOT NULL,
                           name TEXT NOT NULL,
                           storage_location TEXT NOT NULL,
                           media_type VARCHAR(150),
                           size_bytes BIGINT,
                           sha256 VARCHAR(64),
                           created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                           UNIQUE (id, investigation_id),

                           CONSTRAINT fk_artifact_execution_investigation
                               FOREIGN KEY (execution_id, investigation_id)
                                   REFERENCES executions(id, investigation_id),

                           CONSTRAINT chk_artifact_size
                               CHECK (size_bytes IS NULL OR size_bytes >= 0)
);

CREATE TABLE evidence (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          investigation_id UUID NOT NULL REFERENCES investigations(id),
                          execution_id UUID,
                          artifact_id UUID,
                          evidence_type VARCHAR(50) NOT NULL,
                          observation TEXT NOT NULL,
                          source_reference TEXT NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_evidence_execution_investigation
                              FOREIGN KEY (execution_id, investigation_id)
                                  REFERENCES executions(id, investigation_id),

                          CONSTRAINT fk_evidence_artifact_investigation
                              FOREIGN KEY (artifact_id, investigation_id)
                                  REFERENCES artifacts(id, investigation_id)
);

CREATE INDEX idx_snapshots_project
    ON repository_snapshots(project_id);

CREATE INDEX idx_investigations_project_created
    ON investigations(project_id, created_at);

CREATE INDEX idx_investigations_snapshot_project
    ON investigations(snapshot_id, project_id);

CREATE INDEX idx_investigations_workflow_state
    ON investigations(workflow_state);

CREATE INDEX idx_executions_investigation
    ON executions(investigation_id);

CREATE INDEX idx_artifacts_investigation
    ON artifacts(investigation_id);

CREATE INDEX idx_artifacts_execution_investigation
    ON artifacts(execution_id, investigation_id);

CREATE INDEX idx_evidence_investigation
    ON evidence(investigation_id);

CREATE INDEX idx_evidence_execution_investigation
    ON evidence(execution_id, investigation_id);

CREATE INDEX idx_evidence_artifact_investigation
    ON evidence(artifact_id, investigation_id);

CREATE FUNCTION set_updated_at()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
RETURN NEW;
END;
$$;

CREATE TRIGGER projects_set_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER investigations_set_updated_at
    BEFORE UPDATE ON investigations
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
```

## V3__add_snapshot_import_metadata.sql
```sql
ALTER TABLE repository_snapshots
    ADD COLUMN content_hash VARCHAR(64),
    ADD COLUMN build_tool VARCHAR(30),
    ADD COLUMN source_directory TEXT,
    ADD COLUMN test_directory TEXT,
    ADD COLUMN module_path TEXT,
    ADD COLUMN imported_at TIMESTAMPTZ;

ALTER TABLE repository_snapshots
    ADD CONSTRAINT chk_snapshot_content_hash
        CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$');
```

## V4__add_durable_execution_queue.sql
```sql
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
```

## V5__add_java_symbol_indexes.sql
```sql
CREATE TABLE java_symbol_indexes (
    snapshot_id UUID PRIMARY KEY REFERENCES repository_snapshots(id),
    content_hash VARCHAR(64) NOT NULL,
    parser_version VARCHAR(40) NOT NULL,
    document JSONB NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

## V6__add_java_relationship_graphs.sql
```sql
CREATE TABLE java_relationship_graphs (
    snapshot_id UUID PRIMARY KEY REFERENCES repository_snapshots(id),
    content_hash VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(40) NOT NULL,
    document JSONB NOT NULL,
    built_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

## V7__add_semantic_retrieval.sql
```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE semantic_indexes (
    id uuid PRIMARY KEY,
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    content_hash text NOT NULL,
    model_name text NOT NULL,
    model_digest text NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions = 768),
    chunker_version text NOT NULL,
    state text NOT NULL CHECK (state IN ('BUILDING','COMPLETE','PARTIAL','FAILED')),
    chunk_count integer NOT NULL DEFAULT 0,
    diagnostics jsonb NOT NULL DEFAULT '[]',
    technical_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
CREATE INDEX semantic_indexes_snapshot ON semantic_indexes(snapshot_id,created_at DESC);

CREATE TABLE semantic_chunks (
    index_id uuid NOT NULL REFERENCES semantic_indexes(id) ON DELETE CASCADE,
    chunk_key text NOT NULL,
    file_path text NOT NULL,
    start_line integer NOT NULL CHECK (start_line > 0),
    end_line integer NOT NULL CHECK (end_line >= start_line),
    kind text NOT NULL,
    symbol_name text NOT NULL,
    is_test boolean NOT NULL,
    source_text text NOT NULL,
    text_hash text NOT NULL,
    embedding vector(768) NOT NULL,
    PRIMARY KEY(index_id,chunk_key)
);
-- Exact cosine search is sufficient for this bounded fixture index.
-- Add HNSW only after measuring larger collections and filtered-query recall.
```

## V8__add_document_history_context.sql
```sql
CREATE TABLE context_embedding_cache (
    model_digest text NOT NULL,
    input_hash text NOT NULL,
    embedding vector(768) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(model_digest,input_hash)
);

CREATE TABLE context_indexes (
    id uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    content_hash text NOT NULL,
    model_digest text NOT NULL,
    algorithm_version text NOT NULL,
    state text NOT NULL CHECK(state IN ('BUILDING','COMPLETE','FAILED')),
    manifest jsonb NOT NULL DEFAULT '[]',
    statistics jsonb NOT NULL DEFAULT '{}',
    technical_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
CREATE INDEX context_indexes_owner ON context_indexes(investigation_id,created_at DESC);

CREATE TABLE context_chunks (
    index_id uuid NOT NULL REFERENCES context_indexes(id) ON DELETE CASCADE,
    chunk_number integer NOT NULL,
    origin text NOT NULL CHECK(origin IN ('SNAPSHOT_DOCUMENT','SNAPSHOT_CONFIG','HISTORICAL_COMMIT','HISTORICAL_DIFF','PREVIOUS_REPORT','EXECUTION_STDOUT','EXECUTION_STDERR','EXECUTION_RESULT')),
    source_ref text NOT NULL,
    source_revision text NOT NULL,
    line_kind text NOT NULL,
    start_line integer NOT NULL CHECK(start_line>0),
    end_line integer NOT NULL CHECK(end_line>=start_line),
    document_hash text NOT NULL,
    chunk_hash text NOT NULL,
    title text NOT NULL,
    source_text text NOT NULL,
    embedding vector(768) NOT NULL,
    PRIMARY KEY(index_id,chunk_number)
);
```

## V9__add_model_calls.sql
```sql
CREATE TABLE ai_model_calls (
    id uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    request_hash text NOT NULL,
    provider text NOT NULL,
    requested_model text NOT NULL,
    actual_model text,
    prompt_version text NOT NULL,
    question text NOT NULL,
    instructions text NOT NULL,
    user_input text NOT NULL,
    input_evidence jsonb NOT NULL,
    state text NOT NULL CHECK(state IN ('STARTED','SUCCEEDED','INCOMPLETE','FAILED')),
    response_text text,
    provider_request_id text,
    input_tokens bigint CHECK(input_tokens>=0),
    output_tokens bigint CHECK(output_tokens>=0),
    total_tokens bigint CHECK(total_tokens>=0),
    usage_kind text NOT NULL CHECK(usage_kind IN ('UNAVAILABLE','MEASURED','MOCK_NOT_MEASURED')),
    duration_ms bigint CHECK(duration_ms>=0),
    error_code text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
CREATE INDEX ai_calls_investigation ON ai_model_calls(investigation_id,created_at DESC);
```

## V10__add_bounded_agent_roles.sql
```sql
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
```

## V11__add_role_tool_gateway.sql
```sql
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
```

## V12__add_generated_reproduction.sql
```sql
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
```

## V13__add_bounded_investigation_loop.sql
```sql
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
```

## V14__allow_unstarted_execution_errors.sql
```sql
-- A queued job can expire or be cancelled without ever starting repository code.
-- Preserve started_at=NULL; do not fabricate an execution start time.
ALTER TABLE executions DROP CONSTRAINT chk_execution_times;

ALTER TABLE executions ADD CONSTRAINT chk_execution_times CHECK (
    finished_at IS NULL
    OR (started_at IS NOT NULL AND finished_at >= started_at)
    OR (started_at IS NULL AND execution_state IN ('ERROR', 'CANCELLED')
        AND finished_at >= created_at)
);
```

## V15__add_controlled_scenario_batches.sql
```sql
ALTER TABLE investigation_loops ADD COLUMN scenario varchar(50);
ALTER TABLE investigation_loops ADD COLUMN repetitions integer NOT NULL DEFAULT 0;
ALTER TABLE investigation_loops ADD CONSTRAINT chk_loop_scenario_batch CHECK (
    (scenario IS NULL AND repetitions=0)
    OR (scenario IN ('RETRY_AFTER_RESPONSE_LOSS','DELAYED_RESPONSE','CONCURRENT_RETRIES','RETRY_AFTER_COMPLETION')
        AND repetitions BETWEEN 2 AND 30)
);
ALTER TABLE investigation_loop_rounds ADD COLUMN scenario_report jsonb;
```

## V16__add_evidence_linked_diagnoses.sql
```sql
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
```

## V17__add_developer_patch_reviews.sql
```sql
CREATE TABLE developer_patch_reviews (
    id uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    snapshot_id uuid NOT NULL REFERENCES repository_snapshots(id),
    diagnosis_id uuid NOT NULL REFERENCES diagnosis_reports(id),
    patch_id uuid NOT NULL,
    verification_id uuid NOT NULL,
    bundle_hash varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','APPROVED','REJECTED')),
    decision_reason text,
    suite_exception_accepted boolean NOT NULL DEFAULT false,
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(investigation_id,patch_id,verification_id),
    CHECK ((state='PENDING' AND reviewed_at IS NULL) OR (state<>'PENDING' AND reviewed_at IS NOT NULL))
);
CREATE TABLE developer_patch_review_events (
    id bigserial PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES developer_patch_reviews(id),
    action varchar(30) NOT NULL,
    details jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
```

## V18__add_benchmark_case_registrations.sql
```sql
CREATE TABLE benchmark_case_registrations (
    id UUID PRIMARY KEY,
    case_id VARCHAR(16) NOT NULL CHECK (case_id ~ '^case-00[1-6]$'),
    collection_version VARCHAR(32) NOT NULL,
    release_commit VARCHAR(64) NOT NULL,
    repository TEXT NOT NULL,
    module_path TEXT NOT NULL,
    snapshot_content_hash VARCHAR(64) NOT NULL,
    registration JSONB NOT NULL,
    registration_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(repository,release_commit,case_id)
);
```

## V19__add_benchmark_snapshot_imports.sql
```sql
CREATE TABLE benchmark_snapshot_imports (
    investigation_id UUID PRIMARY KEY REFERENCES investigations(id),
    snapshot_id UUID NOT NULL UNIQUE REFERENCES repository_snapshots(id),
    registration_id UUID NOT NULL REFERENCES benchmark_case_registrations(id),
    case_id VARCHAR(16) NOT NULL,
    release_commit VARCHAR(64) NOT NULL,
    benchmark_content_hash VARCHAR(64) NOT NULL CHECK (benchmark_content_hash ~ '^[0-9a-f]{64}$'),
    platform_content_hash VARCHAR(64) NOT NULL CHECK (platform_content_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
```

## V20__add_benchmark_reproduction_runs.sql
```sql
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
```

## V21__add_benchmark_case_records.sql
```sql
ALTER TABLE benchmark_reproduction_runs ADD COLUMN retrieval_requests INTEGER NOT NULL DEFAULT 0 CHECK (retrieval_requests BETWEEN 0 AND 30);
CREATE TABLE benchmark_source_versions (
    run_id UUID NOT NULL REFERENCES benchmark_reproduction_runs(id),
    version INTEGER NOT NULL CHECK (version > 0),
    model_call_id UUID NOT NULL REFERENCES ai_model_calls(id),
    source_sha256 TEXT NOT NULL,
    source_document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, version)
);
CREATE TABLE benchmark_case_records (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES benchmark_reproduction_runs(id),
    investigation_id UUID NOT NULL REFERENCES investigations(id),
    snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id),
    content_hash TEXT NOT NULL,
    collection_version TEXT NOT NULL,
    case_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('EXPORT','RETRIEVAL')),
    document JSONB NOT NULL,
    document_sha256 TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id,kind,document_sha256)
);
CREATE INDEX benchmark_case_records_run ON benchmark_case_records(run_id,created_at DESC);
```

## V22__add_benchmark_browser_requests.sql
```sql
CREATE TABLE benchmark_browser_requests (
    request_id UUID PRIMARY KEY,
    case_id TEXT NOT NULL,
    registration_id UUID NOT NULL REFERENCES benchmark_case_registrations(id),
    investigation_id UUID NOT NULL UNIQUE REFERENCES investigations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
```

## V23__add_benchmark_execution_links.sql
```sql
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
```

## V24__add_project_access_and_audit.sql
```sql
CREATE TABLE team_access_state (id INTEGER PRIMARY KEY CHECK (id=1), initialized BOOLEAN NOT NULL DEFAULT FALSE);
INSERT INTO team_access_state(id) VALUES (1);
CREATE TABLE team_credentials (
 id UUID PRIMARY KEY,
 project_id UUID NOT NULL REFERENCES projects(id),
 display_name VARCHAR(80) NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('HUMAN','WORKER')),
 role TEXT CHECK(role IN ('OWNER','INVESTIGATOR','REVIEWER','VIEWER')),
 token_hash VARCHAR(64) NOT NULL UNIQUE,
 snapshot_id UUID REFERENCES repository_snapshots(id),
 worker_id VARCHAR(64),
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK ((kind='HUMAN' AND role IS NOT NULL AND snapshot_id IS NULL AND worker_id IS NULL)
     OR (kind='WORKER' AND role IS NULL AND snapshot_id IS NOT NULL AND worker_id IS NOT NULL))
);
CREATE INDEX team_credentials_project ON team_credentials(project_id);
CREATE TABLE team_sessions (
 token_hash VARCHAR(64) PRIMARY KEY,
 credential_id UUID NOT NULL REFERENCES team_credentials(id),
 csrf_token VARCHAR(64) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE team_login_limits (client_hash VARCHAR(64) PRIMARY KEY, window_started TIMESTAMPTZ NOT NULL, attempts INTEGER NOT NULL);
CREATE TABLE team_audit_events (
 id UUID PRIMARY KEY,
 request_id UUID NOT NULL,
 project_id UUID REFERENCES projects(id),
 actor_id UUID,
 actor_kind TEXT NOT NULL,
 actor_role TEXT,
 action VARCHAR(120) NOT NULL,
 target VARCHAR(700) NOT NULL,
 outcome VARCHAR(40) NOT NULL,
 http_status INTEGER,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX team_audit_project ON team_audit_events(project_id,created_at DESC);
CREATE FUNCTION team_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Team audit events are append-only';
END;
$$;
CREATE TRIGGER team_audit_no_change BEFORE UPDATE OR DELETE ON team_audit_events FOR EACH ROW EXECUTE FUNCTION team_audit_append_only();
```

## V25__add_worker_presence.sql
```sql
CREATE TABLE worker_presence (
 credential_id UUID PRIMARY KEY REFERENCES team_credentials(id),
 project_id UUID NOT NULL REFERENCES projects(id),
 worker_id VARCHAR(64) NOT NULL,
 snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id),
 content_hash VARCHAR(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
 last_contact_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 readiness VARCHAR(20) NOT NULL CHECK(readiness IN ('BASE_VERIFIED','NOT_READY')),
 current_execution_id UUID
);
CREATE INDEX worker_presence_project ON worker_presence(project_id,last_contact_at DESC);
```
