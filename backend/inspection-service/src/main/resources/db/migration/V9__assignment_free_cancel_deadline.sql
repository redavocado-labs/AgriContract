-- Historical assignment deadlines cannot be reconstructed authoritatively.
ALTER TABLE level1_5_inspection_assignment
    ADD COLUMN free_cancel_until TIMESTAMP(6) NULL;
