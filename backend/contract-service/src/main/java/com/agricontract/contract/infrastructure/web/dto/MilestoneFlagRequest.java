package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.domain.model.milestone.FlagIssueType;

/**
 * MilestoneFlagRequest (golden-flow-api.yaml, D80, D82): the issueType the buyer disputes and the
 * FLAG_MILESTONE_ISSUE consent, wrapped (unlike /confirm and /respond). Unknown fields are refused.
 */
public record MilestoneFlagRequest(FlagIssueType issueType, ConsentInput consent) {
    public MilestoneFlagRequest {
        if (issueType == null) {
            throw new IllegalArgumentException("issueType is required (QUANTITY or QUALITY)");
        }
        if (consent == null) {
            throw new IllegalArgumentException("consent is required");
        }
    }
}
