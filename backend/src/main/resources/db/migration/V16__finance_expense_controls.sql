ALTER TABLE expense_items
    ADD COLUMN receipt_document_version_id UUID REFERENCES document_versions(id),
    ADD COLUMN receipt_sha256 CHAR(64);

UPDATE expense_items item
SET receipt_document_version_id = version.id,
    receipt_sha256 = version.sha256
FROM documents document
JOIN document_versions version ON version.id = document.current_version_id
WHERE item.receipt_document_id = document.id
  AND item.receipt_sha256 IS NULL;

ALTER TABLE expense_items
    ADD CONSTRAINT expense_items_receipt_sha256_check
    CHECK (
      receipt_sha256 IS NULL
      OR receipt_sha256 ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX idx_expense_items_receipt_hash
    ON expense_items(receipt_sha256)
    WHERE receipt_sha256 IS NOT NULL;

CREATE INDEX idx_expense_items_receipt_version
    ON expense_items(receipt_document_version_id)
    WHERE receipt_document_version_id IS NOT NULL;
