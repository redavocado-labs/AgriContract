package com.agricontract.contract.domain.model.breach;

import com.agricontract.contract.domain.model.consent.SignerRole;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A BreachCase as an allegation only (D80): who reported, who is alleged, why. It has no attribution and no remedy;
 * those fields are added with the review and resolution (#135). Opening one has no money or reputation consequence.
 * evidenceFileIds are the evidence files the milestone held when the case was opened.
 */
public record BreachCase(String breachCaseId, String contractId, String milestoneId, BreachRequester requestedBy,
                         SignerRole allegedBreachingRole, BreachReasonCode breachReasonCode, BreachSeverity severity,
                         BreachCaseStatus status, List<String> evidenceFileIds, Instant reportedAt) {
    public BreachCase {
        Objects.requireNonNull(breachCaseId, "breachCaseId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(breachReasonCode, "breachReasonCode");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reportedAt, "reportedAt");
        evidenceFileIds = List.copyOf(evidenceFileIds);
    }

    /** A new REPORTED case opened by the buyer against the seller for a flagged milestone. */
    public static BreachCase reportedByBuyer(String breachCaseId, String contractId, String milestoneId,
                                             BreachReasonCode reason, List<String> evidenceFileIds, Instant now) {
        return new BreachCase(breachCaseId, contractId, milestoneId, BreachRequester.BUYER, SignerRole.SELLER, reason,
                BreachSeverity.MINOR, BreachCaseStatus.REPORTED, evidenceFileIds, now);
    }
}
