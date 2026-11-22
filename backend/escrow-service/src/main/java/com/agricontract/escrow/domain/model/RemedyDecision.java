package com.agricontract.escrow.domain.model;

import java.time.Instant;

/** What escrow decided about one remedy.finalized, keyed by remedyDecisionId so a second event never re-dispatches. */
public record RemedyDecision(String remedyDecisionId, String contractId, String eventId,
                             RemedyDecisionStatus status, String rejectReason, Instant createdAt) {

    public static final int MAX_REASON = 500;

    public static RemedyDecision accepted(String remedyDecisionId, String contractId, String eventId, Instant now) {
        return new RemedyDecision(remedyDecisionId, contractId, eventId, RemedyDecisionStatus.ACCEPTED, null, now);
    }

    public static RemedyDecision rejected(String remedyDecisionId, String contractId, String eventId, String reason,
                                          Instant now) {
        return new RemedyDecision(remedyDecisionId, contractId, eventId, RemedyDecisionStatus.REJECTED,
                reason.substring(0, Math.min(reason.length(), MAX_REASON)), now);
    }
}
