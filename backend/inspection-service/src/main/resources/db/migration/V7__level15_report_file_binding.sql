-- D62: the inspection service owns the file-to-assignment relationship.
CREATE TABLE level1_5_report_file (
    file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    commission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    uploaded_at TIMESTAMP(6) NOT NULL,
    attached_confirmed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_l15_report_file_binding (file_id, commission_id),
    CONSTRAINT fk_l15_report_file_assignment FOREIGN KEY (commission_id)
        REFERENCES level1_5_inspection_assignment (commission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Existing V4 rows remain valid; the submission use case must require a file for new Level 1.5 reports.
ALTER TABLE inspection_report
    ADD COLUMN report_file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_report_l15_file FOREIGN KEY (report_file_id, l15_commission_id)
        REFERENCES level1_5_report_file (file_id, commission_id);
