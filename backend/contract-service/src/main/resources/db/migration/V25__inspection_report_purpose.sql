-- D91 follow-up of #241 (#593): an INCONCLUSIVE re-run no longer uses up the contest. Each report says why it was
-- asked for; a milestone takes at most one of each purpose, so up to three reports (rounds 1..3).
ALTER TABLE inspection_report ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'INITIAL';
-- Existing second reports: a re-run when the first one was INCONCLUSIVE, else the contest report.
UPDATE inspection_report r
    JOIN inspection_report f ON f.milestone_id = r.milestone_id AND f.`round` = 1
SET r.purpose = IF(f.measurement_status = 'INCONCLUSIVE', 'RERUN', 'CONTEST')
WHERE r.`round` = 2;
ALTER TABLE inspection_report
    ADD CONSTRAINT chk_inspection_report_purpose CHECK (purpose IN ('INITIAL', 'RERUN', 'CONTEST')),
    ADD CONSTRAINT uk_inspection_report_milestone_purpose UNIQUE (milestone_id, purpose);
ALTER TABLE inspection_report DROP CHECK chk_inspection_report_round;
ALTER TABLE inspection_report ADD CONSTRAINT chk_inspection_report_round CHECK (`round` IN (1, 2, 3));
