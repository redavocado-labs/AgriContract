-- #122, milestone-escrow design §3.2.2, D76: the buyer's receipt (step 1) on the milestone. No correction, so one
-- receipt per milestone and no history table; the consent is kept in consent_evidence.
ALTER TABLE milestone
    ADD COLUMN buyer_received_weight       DECIMAL(10,3) NULL,
    ADD COLUMN buyer_evidence_file_id      VARCHAR(36)   NULL,
    ADD COLUMN buyer_certificate_file_id   VARCHAR(36)   NULL,
    ADD COLUMN buyer_received_at           TIMESTAMP(6)  NULL,
    ADD CONSTRAINT chk_milestone_buyer_received CHECK (
        (buyer_received_weight IS NULL) = (buyer_evidence_file_id IS NULL)
        AND (buyer_received_weight IS NULL) = (buyer_received_at IS NULL)
        AND (buyer_certificate_file_id IS NULL OR buyer_certificate_file_id <> buyer_evidence_file_id)),
    -- D72 bounds, as the seller's weight.
    ADD CONSTRAINT chk_milestone_buyer_weight CHECK (buyer_received_weight IS NULL
        OR (buyer_received_weight > 0 AND buyer_received_weight <= 1000000));
