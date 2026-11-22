-- #241, D85/D91: the contest window (GB-50), the contest itself, the second Level 1.5 report and the superseded
-- RemedyDecision. All additive: existing rows stay valid (round 1, contest_* NULL).
-- breach_case: what the contesting party said (the one-time contest of the decision) and who/when.
ALTER TABLE breach_case
    ADD COLUMN contest_reason            VARCHAR(1000) NULL,
    ADD COLUMN contest_evidence_file_ids JSON          NULL,
    ADD COLUMN contested_at              TIMESTAMP(6)  NULL,
    ADD COLUMN contested_by_actor_id     VARCHAR(36)   NULL;

-- inspection_report: a milestone can have two reports (a re-run after INCONCLUSIVE, or after a contest). The new unique
-- is added first: fk_inspection_report_milestone needs an index that starts with milestone_id.
ALTER TABLE inspection_report ADD COLUMN `round` TINYINT NOT NULL DEFAULT 1;
ALTER TABLE inspection_report ADD CONSTRAINT uk_inspection_report_milestone_round UNIQUE (milestone_id, `round`);
ALTER TABLE inspection_report DROP INDEX uk_inspection_report_milestone;
ALTER TABLE inspection_report ADD CONSTRAINT chk_inspection_report_round CHECK (`round` IN (1, 2));

-- remedy_decision: a contest that wins a new Level 1.5 decision supersedes the pending one (no event); the new
-- decision is round 2 of the same case. Same order for the unique: fk_remedy_decision_breach_case needs an index.
ALTER TABLE remedy_decision
    ADD COLUMN `round`        TINYINT      NOT NULL DEFAULT 1,
    ADD COLUMN superseded_at  TIMESTAMP(6) NULL;
ALTER TABLE remedy_decision ADD CONSTRAINT uk_remedy_decision_breach_case_round UNIQUE (breach_case_id, `round`);
ALTER TABLE remedy_decision DROP INDEX uk_remedy_decision_breach_case;
ALTER TABLE remedy_decision DROP CHECK chk_remedy_decision_status;
ALTER TABLE remedy_decision
    ADD CONSTRAINT chk_remedy_decision_status CHECK (status IN ('PENDING_CONTEST', 'FINALIZED', 'SUPERSEDED')),
    ADD CONSTRAINT chk_remedy_decision_superseded CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
    ADD CONSTRAINT chk_remedy_decision_round CHECK (`round` IN (1, 2));
