package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;

import java.util.List;
import java.util.UUID;

/** reviewBreachCase, resolveBreachCase, the contest of a decision and the reads of a breach case (D80, D81, D84, D91). */
public final class BreachCaseCommands {
    private BreachCaseCommands() {
    }

    /** reviewBreachCase: an ARBITRATOR takes the case. */
    public record Review(String contractId, String breachCaseId, String actorId, UUID idempotencyKey) {
    }

    /** resolveBreachCase: finalBreachingRole null means no fault. */
    public record Resolve(String contractId, String breachCaseId, String actorId, UUID idempotencyKey,
                          SignerRole finalBreachingRole, DecisionSource decisionSource, List<String> evidenceRefs) {
    }

    /** contestBreachDecision (D91): reason 1..1000 characters, at most 10 unique evidence file ids. */
    public record Contest(String contractId, String breachCaseId, String actorId, UUID idempotencyKey, String reason,
                          List<String> evidenceFileIds) {
    }

    /** contestBreachDecisionEvidence (D91): 1..10 unique file ids to add to the contest. */
    public record AddContestEvidence(String contractId, String breachCaseId, String actorId, UUID idempotencyKey,
                                     List<String> fileIds) {
    }

    /** One page of the Arbitrator queue. */
    public record BreachCasePageView(List<BreachCase> content, int page, int size, long totalElements,
                                     int totalPages) {
    }
}
