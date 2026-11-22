-- #121, milestone-escrow design §3.2.1, D71-D75: seller weighing and the evidence files behind it.

-- The declaration in force on the milestone (the latest one). seller_first_declared_at starts the correction window
-- (D75); a correction never moves it.
ALTER TABLE milestone
    ADD COLUMN seller_declared_weight             DECIMAL(10,3) NULL,
    ADD COLUMN seller_evidence_file_id            VARCHAR(36)   NULL,
    ADD COLUMN seller_delivery_certificate_file_id VARCHAR(36)  NULL,
    ADD COLUMN seller_first_declared_at           TIMESTAMP(6)  NULL,
    ADD COLUMN seller_declaration_count           INT           NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_milestone_seller_declared CHECK (
        (seller_declaration_count = 0) = (seller_declared_weight IS NULL)
        AND (seller_declaration_count = 0) = (seller_evidence_file_id IS NULL)
        AND (seller_declaration_count = 0) = (seller_first_declared_at IS NULL)
        AND (seller_declaration_count = 0 OR seller_delivery_certificate_file_id IS NULL
             OR seller_delivery_certificate_file_id <> seller_evidence_file_id)),
    -- D72: more than 0, at most 1,000,000 kg (three decimals by the column type).
    ADD CONSTRAINT chk_milestone_seller_weight CHECK (seller_declared_weight IS NULL
        OR (seller_declared_weight > 0 AND seller_declared_weight <= 1000000)),
    ADD CONSTRAINT chk_milestone_seller_count CHECK (seller_declaration_count >= 0),
    -- A milestone is weighed from SELLER_WEIGHED on, never before.
    ADD CONSTRAINT chk_milestone_weighed_state CHECK ((seller_declaration_count > 0
        OR status IN ('CREATED', 'IN_PROGRESS', 'FORCE_MAJEURE_PENDING_REVIEW', 'SETTLED'))
        AND (seller_declaration_count = 0 OR status NOT IN ('CREATED', 'IN_PROGRESS')));

-- Every declaration of the seller, the first and each correction (D75). Append-only, as consent_evidence.
CREATE TABLE seller_weigh_declaration (
    declaration_id                CHAR(36)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    milestone_id                  VARCHAR(36)   NOT NULL,
    contract_id                   VARCHAR(255)  NOT NULL,
    declaration_number            INT           NOT NULL,
    seller_declared_weight        DECIMAL(10,3) NOT NULL,
    seller_evidence_file_id       VARCHAR(36)   NOT NULL,
    delivery_certificate_file_id  VARCHAR(36)   NULL,
    consent_id                    CHAR(36)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    declared_by                   VARCHAR(255)  NOT NULL,
    declared_at                   TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (declaration_id),
    CONSTRAINT uk_seller_weigh_declaration_number UNIQUE (milestone_id, declaration_number),
    CONSTRAINT fk_seller_weigh_declaration_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT fk_seller_weigh_declaration_consent FOREIGN KEY (consent_id) REFERENCES consent_evidence (consent_id),
    CONSTRAINT chk_seller_weigh_declaration_number CHECK (declaration_number >= 1),
    CONSTRAINT chk_seller_weigh_declaration_weight CHECK (seller_declared_weight > 0
        AND seller_declared_weight <= 1000000)
);

CREATE TRIGGER trg_seller_weigh_declaration_no_update BEFORE UPDATE ON seller_weigh_declaration
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'seller_weigh_declaration is append-only';

CREATE TRIGGER trg_seller_weigh_declaration_no_delete BEFORE DELETE ON seller_weigh_declaration
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'seller_weigh_declaration is append-only';

-- D71: a file a party uploaded for a milestone through contract-service. Only such a file can be named by weigh (and
-- later receive/confirm/flag). (uploaded_by, idempotency_key) is the upload, as in file-service: a repeat finds the
-- same row. named_at: a declaration uses the file, so it must be kept; attached_confirmed_at: file-service confirmed
-- it (confirm-attached), retried while NULL.
CREATE TABLE milestone_evidence_file (
    file_id                VARCHAR(36)  NOT NULL,
    milestone_id           VARCHAR(36)  NOT NULL,
    contract_id            VARCHAR(255) NOT NULL,
    uploaded_by            VARCHAR(255) NOT NULL,
    uploader_role          VARCHAR(16)  NOT NULL,
    idempotency_key        VARCHAR(36)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    uploaded_at            TIMESTAMP(6) NOT NULL,
    named_at               TIMESTAMP(6) NULL,
    attached_confirmed_at  TIMESTAMP(6) NULL,
    PRIMARY KEY (file_id),
    CONSTRAINT uk_milestone_evidence_upload UNIQUE (uploaded_by, idempotency_key),
    CONSTRAINT fk_milestone_evidence_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT chk_milestone_evidence_role CHECK (uploader_role IN ('BUYER', 'SELLER')),
    CONSTRAINT chk_milestone_evidence_attached CHECK (attached_confirmed_at IS NULL OR named_at IS NOT NULL)
);

CREATE INDEX idx_milestone_evidence_pending_attach ON milestone_evidence_file (attached_confirmed_at, named_at);

-- file.ready / file.failed as file-service reported them, by fileId. Kept even for a file no milestone names yet: a
-- fast scan can be reported before the upload that produced the file is recorded. The first outcome stays.
CREATE TABLE file_scan_outcome (
    file_id         VARCHAR(36)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    failure_reason  VARCHAR(500) NULL,
    received_at     TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (file_id),
    CONSTRAINT chk_file_scan_outcome_status CHECK (status IN ('READY', 'FAILED'))
);
