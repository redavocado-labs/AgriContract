ALTER TABLE level1_5_inspection_assignment
    ADD COLUMN free_cancel_until TIMESTAMP(6) NULL;

-- D57 historical baseline approved for assignments created before V9.
UPDATE level1_5_inspection_assignment
SET free_cancel_until = assigned_at + INTERVAL 4 HOUR
WHERE free_cancel_until IS NULL;

ALTER TABLE level1_5_inspection_assignment
    MODIFY COLUMN free_cancel_until TIMESTAMP(6) NOT NULL;
