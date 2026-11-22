CREATE TABLE level1_5_report_submission_receipt (
    inspector_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARBINARY(255) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (inspector_id, idempotency_key),
    UNIQUE KEY uk_report_submission_receipt_report (report_id),
    CONSTRAINT fk_report_submission_receipt_report FOREIGN KEY (report_id) REFERENCES inspection_report(report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
