package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.inspection.InspectionReportPurpose;
import com.agricontract.contract.domain.model.inspection.InspectionReport;
import com.agricontract.contract.domain.model.quality.QualityAssessment;

import java.util.Optional;

/**
 * inspection_report and quality_assessment (V22): immutable, insert only, at most one report per purpose and
 * milestone, so three (V25).
 */
public interface InspectionReportRepository {

    /** Stores a report with the assessment computed from it and why it was asked for, in the caller's transaction. */
    void insert(InspectionReport report, QualityAssessment assessment, InspectionReportPurpose purpose);

    /** True when the milestone already has a report asked for this purpose. */
    boolean existsByMilestoneIdAndPurpose(String milestoneId, InspectionReportPurpose purpose);

    /** The latest report of the milestone. */
    Optional<InspectionReport> findByMilestoneId(String milestoneId);

    /** How many reports (rounds) are stored for the milestone: 0 to 3 (first, re-run after INCONCLUSIVE, contest). */
    int countByMilestoneId(String milestoneId);

    /** True when a COMPLETE report (one that decides money) is stored for the milestone. */
    boolean existsCompleteByMilestoneId(String milestoneId);
}
