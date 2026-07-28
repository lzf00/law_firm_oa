ALTER TABLE document_versions
    ADD COLUMN ingestion_status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN detected_content_type VARCHAR(150),
    ADD COLUMN scan_provider VARCHAR(80),
    ADD COLUMN scan_result VARCHAR(80),
    ADD COLUMN scan_failure_reason VARCHAR(500),
    ADD COLUMN scan_started_at TIMESTAMPTZ,
    ADD COLUMN scan_completed_at TIMESTAMPTZ;

ALTER TABLE document_versions
    ADD CONSTRAINT chk_document_ingestion_status
    CHECK (ingestion_status IN ('QUARANTINED', 'SCANNING', 'AVAILABLE', 'REJECTED', 'FAILED'));

ALTER TABLE document_upload_sessions
    ADD COLUMN cleaned_at TIMESTAMPTZ;

ALTER TABLE document_upload_sessions
    DROP CONSTRAINT document_upload_sessions_status_check;

ALTER TABLE document_upload_sessions
    ADD CONSTRAINT document_upload_sessions_status_check
    CHECK (status IN ('PENDING', 'SCANNING', 'COMPLETED', 'REJECTED', 'EXPIRED', 'FAILED'));

CREATE INDEX idx_document_versions_ingestion
    ON document_versions(ingestion_status, scan_started_at)
    WHERE ingestion_status <> 'AVAILABLE';

CREATE INDEX idx_upload_sessions_cleanup
    ON document_upload_sessions(status, expires_at, created_at)
    WHERE status <> 'COMPLETED';
