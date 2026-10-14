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
