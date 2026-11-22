package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * BreachCase (golden-flow-api.yaml, D84). Every key is always present; nulls are written as null. contestStatus is
 * NOT_APPLICABLE and contestDeadline null until the contest window exists (#241).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BreachCaseResponse(String breachCaseId, String contractId, String milestoneId, String requestedBy,
                                 String allegedBreachingRole, String breachReasonCode, String severity,
                                 List<String> evidenceFileIds, String finalBreachingRole, String decisionSource,
                                 String status, String contestStatus, String contestDeadline,
                                 ComputedResolutionView computedResolution, String reportedAt, String resolvedAt) {

    public static BreachCaseResponse from(BreachCase c) {
        return new BreachCaseResponse(c.breachCaseId(), c.contractId(), c.milestoneId(), c.requestedBy().name(),
                name(c.allegedBreachingRole()), c.breachReasonCode().name(), c.severity().name(),
                c.evidenceFileIds(), name(c.finalBreachingRole()), name(c.decisionSource()), c.status().name(),
                c.contestStatus().name(), c.contestDeadline() == null ? null : c.contestDeadline().toString(),
                c.computedResolution() == null ? null : ComputedResolutionView.from(c.computedResolution()),
                c.reportedAt().toString(), c.resolvedAt() == null ? null : c.resolvedAt().toString());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ComputedResolutionView(String inspectionReportId, String issueType, BigDecimal acceptedQuantityKg,
                                         String qualityDisposition, String finalBreachingRole, String decisionSource,
                                         String sellerEntitlementAmount, String buyerRefundAmount,
                                         String prevailingRole) {
        static ComputedResolutionView from(BreachCaseComputedResolution r) {
            return new ComputedResolutionView(r.inspectionReportId(), r.issueType().name(),
                    new BigDecimal(r.acceptedQuantityKg().stripTrailingZeros().toPlainString()),
                    name(r.qualityDisposition()), name(r.finalBreachingRole()), "INSPECTOR_L1_5",
                    r.sellerEntitlementAmount().wire(), r.buyerRefundAmount().wire(), r.prevailingRole().name());
        }
    }

    /** BreachCasePage: exactly these five keys. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<BreachCaseResponse> content, int page, int size, long totalElements, int totalPages) {
        public static Page from(BreachCaseCommands.BreachCasePageView view) {
            return new Page(view.content().stream().map(BreachCaseResponse::from).toList(), view.page(),
                    view.size(), view.totalElements(), view.totalPages());
        }
    }
}
