package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;

import java.util.List;
import java.util.UUID;

/** reviewBreachCase, resolveBreachCase and the reads of a breach case (D80, D81, D84). */
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

    /** One page of the Arbitrator queue. */
    public record BreachCasePageView(List<BreachCase> content, int page, int size, long totalElements,
                                     int totalPages) {
    }
}
