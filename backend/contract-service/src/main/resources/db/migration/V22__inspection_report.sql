-- #246, D81/D85/D89/D90: the confirmed Level 1.5 inspection report and the quality assessment computed from it.
-- Both tables are immutable, insert only: no code updates or deletes a row. One report per milestone for now
-- (UNIQUE milestone_id); the Level 1.5 re-run (#241) will relax it. received_event_id is the eventId of the
-- inspection.report_confirmed that delivered the report.
CREATE TABLE inspection_report (
    report_id            VARCHAR(36)    NOT NULL,
    contract_id          VARCHAR(255)   NOT NULL,
    milestone_id         VARCHAR(36)    NOT NULL,
    tier                 VARCHAR(16)    NOT NULL,
    measurement_status   VARCHAR(16)    NOT NULL,
    measured_quantity_kg DECIMAL(13, 3) NOT NULL,
    accepted_quantity_kg DECIMAL(13, 3) NOT NULL,
    actual_metrics       JSON           NULL,
    result_hash          CHAR(64)       NOT NULL,
    report_hash          CHAR(64)       NOT NULL,
    confirmed_at         TIMESTAMP(6)   NOT NULL,
    received_event_id    VARCHAR(36)    NOT NULL,
    created_at           TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT uk_inspection_report_milestone UNIQUE (milestone_id),
    CONSTRAINT uk_inspection_report_event UNIQUE (received_event_id),
    CONSTRAINT fk_inspection_report_contract FOREIGN KEY (contract_id) REFERENCES contracts (contract_id),
    CONSTRAINT fk_inspection_report_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT chk_inspection_report_tier CHECK (tier IN ('LEVEL_1_5', 'LEVEL_2')),
    CONSTRAINT chk_inspection_report_status CHECK (measurement_status IN ('COMPLETE', 'INCONCLUSIVE')),
    CONSTRAINT chk_inspection_report_quantity CHECK (measured_quantity_kg >= 0
        AND accepted_quantity_kg >= 0 AND accepted_quantity_kg <= measured_quantity_kg),
    CONSTRAINT chk_inspection_report_metrics CHECK (measurement_status = 'INCONCLUSIVE' OR actual_metrics IS NOT NULL)
);

-- Money is whole dong (DECIMAL(19,2), the wire form "144060000.00"); every money column and the rate are NULL for an
-- INCONCLUSIVE disposition, which decides nothing.
CREATE TABLE quality_assessment (
    report_id                      VARCHAR(36)   NOT NULL,
    disposition                    VARCHAR(24)   NOT NULL,
    type_outcome                   VARCHAR(16)   NULL,
    metric_outcomes                JSON          NOT NULL,
    quality_discount_rate          DECIMAL(7, 4) NULL,
    quality_discount_amount        DECIMAL(19, 2) NULL,
    quantity_adjusted_entitlement  DECIMAL(19, 2) NULL,
    final_seller_entitlement       DECIMAL(19, 2) NULL,
    buyer_refund_amount            DECIMAL(19, 2) NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT fk_quality_assessment_report FOREIGN KEY (report_id) REFERENCES inspection_report (report_id),
    CONSTRAINT chk_quality_assessment_disposition CHECK (disposition IN ('CONFORMING', 'PARTIALLY_CONFORMING',
        'NON_CONFORMING', 'INCONCLUSIVE')),
    CONSTRAINT chk_quality_assessment_money CHECK ((disposition = 'INCONCLUSIVE') = (final_seller_entitlement IS NULL)
        AND (disposition = 'INCONCLUSIVE') = (buyer_refund_amount IS NULL))
);

-- D77: a mandatory-inspection milestone whose report is CONFORMING settles on the report (INSPECTED_CONFORMING).
ALTER TABLE milestone DROP CHECK chk_milestone_settlement;
ALTER TABLE milestone
    ADD CONSTRAINT chk_milestone_settlement CHECK (
        (settlement_path IS NULL) = (billable_quantity_kg IS NULL)
        AND (settlement_path IS NULL) = (seller_amount IS NULL)
        AND (settlement_path IS NULL) = (buyer_refund_amount IS NULL)
        AND (settlement_path IS NULL) = (settled_at IS NULL)
        AND (settlement_path IS NULL OR settlement_path IN ('CLEAN_CONFIRMED', 'CLEAN_AUTO_CONFIRMED',
             'SELLER_SILENT_QUANTITY', 'INSPECTED_CONFORMING'))
        AND (seller_amount IS NULL OR (seller_amount >= 0 AND buyer_refund_amount >= 0
             AND seller_amount + buyer_refund_amount = batch_amount)));
