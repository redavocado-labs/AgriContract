package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.inspection.InspectionReport;
import com.agricontract.contract.domain.model.quality.QualityAssessment;

import java.util.Optional;

/** inspection_report and quality_assessment (V22): immutable, insert only, at most two per milestone (V24 rounds). */
public interface InspectionReportRepository {

    /** Stores a report with the assessment computed from it, inside the caller's transaction. */
    void insert(InspectionReport report, QualityAssessment assessment);

    /** The latest report of the milestone. */
    Optional<InspectionReport> findByMilestoneId(String milestoneId);

    /** How many reports (rounds) are stored for the milestone: 0, 1 or 2 (a re-run after INCONCLUSIVE or a contest). */
    int countByMilestoneId(String milestoneId);

    /** True when a COMPLETE report (one that decides money) is stored for the milestone. */
    boolean existsCompleteByMilestoneId(String milestoneId);
}
