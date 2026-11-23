package com.agricontract.contract.domain.model.inspection;

/**
 * Why a Level 1.5 report of a milestone was asked for (D85, D91). A milestone takes at most one of each, so at most
 * three reports: the first one, one re-run after an INCONCLUSIVE first report, and the one a contest asks for. The
 * re-run never uses up the contest.
 */
public enum InspectionReportPurpose {
    INITIAL,
    RERUN,
    CONTEST
}
