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
