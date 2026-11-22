-- #135, D80/D81: the AttributionDecision on a BreachCase and the computed resolution the Arbitrator approves.
-- All additive. final_breaching_role is NULL both before RESOLVED and for a no-fault decision; status = RESOLVED is
-- what says a decision exists. contest_* stay NOT_APPLICABLE / NULL until #241 opens the contest window.
ALTER TABLE breach_case
    ADD COLUMN final_breaching_role   VARCHAR(16)  NULL,
    ADD COLUMN decision_source        VARCHAR(16)  NULL,
    ADD COLUMN decision_evidence_refs JSON         NULL,
    ADD COLUMN resolved_at            TIMESTAMP(6) NULL,
    ADD COLUMN resolved_by_actor_id   VARCHAR(36)  NULL,
    ADD COLUMN contest_status         VARCHAR(16)  NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN contest_deadline       TIMESTAMP(6) NULL,
    ADD COLUMN computed_resolution    JSON         NULL,
    ADD CONSTRAINT chk_breach_case_resolved CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL AND decision_source IS NOT NULL));

CREATE INDEX idx_breach_case_status_reported ON breach_case (status, reported_at);
