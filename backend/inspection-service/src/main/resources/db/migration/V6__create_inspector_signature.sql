CREATE TABLE inspector_signature (
    signature_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signer_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    auth_time TIMESTAMP(6) NOT NULL,
    signed_at TIMESTAMP(6) NOT NULL,
    report_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ip_address VARCHAR(45) NULL,
    CONSTRAINT uk_inspector_signature_report UNIQUE (report_id),
    CONSTRAINT fk_inspector_signature_report FOREIGN KEY (report_id) REFERENCES inspection_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
