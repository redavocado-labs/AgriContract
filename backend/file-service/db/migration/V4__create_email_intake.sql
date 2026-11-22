-- #275, additive: the email intake bridge (file-service-phase2-design.md §4.1, §8).

-- One row per intake mail processed, written in the same transaction as its failures and its
-- file.email_notice (if any): a mail polled again (not flagged \Seen before a crash) is skipped, so every
-- notice publishes once and every attachment is stored once (the store is also idempotent per attachment).
-- message_key = SHA-256 hex of the Message-ID, or of UIDVALIDITY:UID when the mail has none.
CREATE TABLE email_intake_message (
    message_key     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    source_mailbox  VARCHAR(255) NOT NULL,
    attachments     INT NOT NULL,           -- attachments stored as files (EMAIL_INTAKE)
    failures        INT NOT NULL,           -- rows written to email_intake_failure
    processed_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Design §8, as written there (in MySQL syntax): what the Operator must follow up with the sender
-- (an attachment of a type not accepted, over 20 MiB, a mail that cannot be parsed). file_id stays NULL
-- when no file was created, which is every case the bridge records.
CREATE TABLE email_intake_failure (
    failure_id        CHAR(36) PRIMARY KEY,
    file_id           CHAR(36) NULL,
    source_mailbox    VARCHAR(255) NOT NULL,
    failure_reason    TEXT NOT NULL,
    detected_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by_admin BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_email_intake_failure_file FOREIGN KEY (file_id) REFERENCES file(file_id)
);
CREATE INDEX idx_email_intake_failure_review ON email_intake_failure(reviewed_by_admin, detected_at);
