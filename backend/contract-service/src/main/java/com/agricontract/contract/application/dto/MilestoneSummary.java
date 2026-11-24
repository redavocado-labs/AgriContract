package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.time.LocalDate;

/**
 * MilestoneResponse of golden-flow-api.yaml: the milestone plus the two values computed for it, shared by
 * getMilestoneDetail and getContract (D99).
 *
 * @param effectiveUnitPrice the milestone's own price once funding was requested, the signed price before
 */
public record MilestoneSummary(Milestone milestone, VndAmount effectiveUnitPrice, LocalDate effectiveDeliveryDeadline) {
}
