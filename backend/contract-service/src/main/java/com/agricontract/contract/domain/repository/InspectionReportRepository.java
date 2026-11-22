package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.inspection.InspectionReport;
import com.agricontract.contract.domain.model.quality.QualityAssessment;

import java.util.Optional;

/** inspection_report and quality_assessment (V22): immutable, insert only, one report per milestone for now. */
public interface InspectionReportRepository {

    /** Stores a report with the assessment computed from it, inside the caller's transaction. */
    void insert(InspectionReport report, QualityAssessment assessment);

    Optional<InspectionReport> findByMilestoneId(String milestoneId);

    boolean existsByMilestoneId(String milestoneId);

    /** True when a COMPLETE report (one that decides money) is stored for the milestone. */
    boolean existsCompleteByMilestoneId(String milestoneId);
}
