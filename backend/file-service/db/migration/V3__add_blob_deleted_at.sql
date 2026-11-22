-- #276, additive. Step 2 of the two-step deletion (file-service-phase2-design.md §7) leaves a mark: blob_deleted_at
-- is set once the blob of a tombstoned row is gone. The lifecycle job finishes every row with deleted_at set and
-- blob_deleted_at still NULL, so a blob whose delete failed after the tombstone is removed on the next run (matrix
-- 21e), and rows already finished are never touched again.
ALTER TABLE file ADD COLUMN blob_deleted_at TIMESTAMP NULL AFTER deletion_reason;
CREATE INDEX idx_file_blob_pending ON file(blob_deleted_at, deleted_at);
