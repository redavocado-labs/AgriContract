package com.agricontract.contract.application.port;

import com.agricontract.contract.domain.model.consent.SignerRole;

import java.util.List;

/**
 * The BF-04 statusType values of notification.milestone_status_requested (D96) and who receives each. FORCE_MAJEURE,
 * LEVEL2_* and PENALTY belong to later flows (PENALTY is already in remedy_finalized_requested).
 */
public enum MilestoneStatusMail {
    /** The seller weighed (each declaration, D75): the buyer is told to receive and weigh. */
    WEIGHED(List.of(SignerRole.BUYER)),
    /** The milestone settled without a dispute decision: buyer confirm, timeout, inspection pass, seller silence. */
    CONFIRMED(List.of(SignerRole.BUYER, SignerRole.SELLER)),
    /** The buyer flagged the milestone: the seller must respond before the deadline (D82). */
    FLAGGED(List.of(SignerRole.SELLER)),
    /** buyerReceiveWindowDays passed since the seller weighed and the buyer has not received (design §3.2). */
    BUYER_RECEIVE_REMINDER(List.of(SignerRole.BUYER));

    private final List<SignerRole> recipients;

    MilestoneStatusMail(List<SignerRole> recipients) {
        this.recipients = recipients;
    }

    public List<SignerRole> recipients() {
        return recipients;
    }
}
