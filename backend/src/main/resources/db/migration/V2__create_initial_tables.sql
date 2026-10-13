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