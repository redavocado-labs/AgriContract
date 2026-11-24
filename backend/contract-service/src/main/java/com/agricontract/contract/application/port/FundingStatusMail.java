package com.agricontract.contract.application.port;

import com.agricontract.contract.domain.model.consent.SignerRole;

import java.util.List;

/**
 * The BF-04 statusType values of notification.milestone_funding_status_requested (D96). FUNDING_CURE_DEADLINE is BF-05
 * (#239). FUNDING_FAILED also goes to the operator email of contract-service, when one is configured.
 */
public enum FundingStatusMail {
    /** Two business days before the milestone's funding date: the buyer makes sure the account holds the batch. */
    FUNDING_REMINDER(List.of(SignerRole.BUYER), false),
    /** escrow could not lock the batch after its retries (D69). */
    FUNDING_FAILED(List.of(SignerRole.BUYER, SignerRole.SELLER), true);

    private final List<SignerRole> recipients;
    private final boolean operator;

    FundingStatusMail(List<SignerRole> recipients, boolean operator) {
        this.recipients = recipients;
        this.operator = operator;
    }

    public List<SignerRole> recipients() {
        return recipients;
    }

    public boolean operator() {
        return operator;
    }
}
