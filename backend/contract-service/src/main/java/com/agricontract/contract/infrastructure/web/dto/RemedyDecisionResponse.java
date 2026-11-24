package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** RemedyDecisionView of golden-flow-api.yaml (D99): a decision as the parties read it. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RemedyDecisionResponse(String remedyDecisionId, String status, String decisionSource,
                                     String finalBreachingRole, String breachReasonCode, String qualityDisposition,
                                     String penaltyAmount, String penaltyDebtAmount, String inspectionFeeParty,
                                     int settledMilestoneCount, String createdAt, String finalizedAt,
                                     List<Leg> remedyLegs) {

    public record Leg(String remedyLegId, String remedyType, String fundType, String role, String amount) {
    }

    public static RemedyDecisionResponse from(RemedyDecision d) {
        return new RemedyDecisionResponse(d.remedyDecisionId(), d.status().name(), d.decisionSource().name(),
                name(d.finalBreachingRole()), name(d.breachReasonCode()), name(d.qualityDisposition()),
                d.penaltyAmount().wire(), d.penaltyDebtAmount().wire(), name(d.inspectionFeeParty()),
                d.settledMilestoneCount(), d.createdAt().toString(),
                d.finalizedAt() == null ? null : d.finalizedAt().toString(),
                d.legs().stream().map(l -> new Leg(l.remedyLegId(), l.remedyType().name(), l.fundType().name(),
                        l.role().name(), l.amount().wire())).toList());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
