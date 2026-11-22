package com.agricontract.escrow.application.dto;

import com.agricontract.escrow.domain.model.RemedyLegInput;

import java.util.List;

/**
 * remedy.finalized (golden-flow-events.yaml remedyFinalized), parsed and checked by the consumer. Escrow keeps only
 * what it moves money with: the decision, the contract and its parties, the affected milestones and the legs.
 */
public record RemedyFinalizedCommand(
        String eventId,
        String correlationId,
        String remedyDecisionId,
        String contractId,
        String buyerId,
        String sellerId,
        List<String> affectedMilestoneIds,
        List<RemedyLegInput> legs) {
}
