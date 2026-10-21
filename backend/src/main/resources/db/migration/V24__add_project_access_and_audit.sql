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
