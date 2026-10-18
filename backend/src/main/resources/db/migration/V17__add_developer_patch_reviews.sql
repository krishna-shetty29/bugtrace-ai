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
