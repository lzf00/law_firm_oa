ALTER TABLE archive_volumes
    ADD COLUMN matter_id UUID REFERENCES matters(id);

CREATE INDEX idx_archive_volumes_matter
    ON archive_volumes(matter_id, created_at DESC)
    WHERE matter_id IS NOT NULL;
