-- #123, D77/D78: how a milestone settled. All nullable until SETTLED.
-- seller_amount + buyer_refund_amount = batch_amount (whole dong, same type as V16).
ALTER TABLE milestone
    ADD COLUMN settlement_path        VARCHAR(32)   NULL,
    ADD COLUMN billable_quantity_kg   DECIMAL(12,4) NULL,
    ADD COLUMN seller_amount          DECIMAL(18,2) NULL,
    ADD COLUMN buyer_refund_amount    DECIMAL(18,2) NULL,
    ADD COLUMN settled_at             TIMESTAMP(6)  NULL,
    ADD CONSTRAINT chk_milestone_settlement CHECK (
        (settlement_path IS NULL) = (billable_quantity_kg IS NULL)
        AND (settlement_path IS NULL) = (seller_amount IS NULL)
        AND (settlement_path IS NULL) = (buyer_refund_amount IS NULL)
        AND (settlement_path IS NULL) = (settled_at IS NULL)
        AND (settlement_path IS NULL OR settlement_path IN ('CLEAN_CONFIRMED', 'CLEAN_AUTO_CONFIRMED'))
        AND (seller_amount IS NULL OR (seller_amount >= 0 AND buyer_refund_amount >= 0
             AND seller_amount + buyer_refund_amount = batch_amount)));
