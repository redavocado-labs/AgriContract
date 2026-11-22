package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.application.dto.MilestoneDetailView;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.milestone.SellerWeight;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * MilestoneDetailResponse (golden-flow-api.yaml, D84). Every key is always present; a block is null until its step
 * has happened. buyerReceipt, flag, settlement and breachCaseId are filled by the issues that produce them (#122
 * onward); until then they are null, which is also their true value before those steps.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MilestoneDetailResponse(String contractId, MilestoneResponse milestone,
                                      SellerDeclarationView sellerDeclaration, Object buyerReceipt, Object flag,
                                      List<EvidenceView> evidence, Object settlement, String breachCaseId) {

    public static MilestoneDetailResponse from(MilestoneDetailView view) {
        Milestone m = view.milestone();
        MilestoneResponse summary = new MilestoneResponse(m.milestoneId(), m.milestoneIndex(), m.status().name(),
                m.fundingStatus().name(), m.fundingScheduledDate().toString(), kg(m.committedQuantity()),
                m.batchAmount() == null ? null : m.batchAmount().wire(), view.effectiveUnitPrice().wire(), null,
                m.expectedDeliveryDate().toString(), m.graceDays(), m.fundingDelayBusinessDays(),
                view.effectiveDeliveryDeadline().toString());
        SellerDeclarationView declaration = null;
        if (view.sellerDeclaration() != null) {
            SellerWeighDeclaration d = view.sellerDeclaration().declaration();
            declaration = new SellerDeclarationView(d.declarationNumber(), kg(d.declaredWeight()), d.evidenceFileId(),
                    d.deliveryCertificateFileId(), d.declaredAt().toString(),
                    view.sellerDeclaration().correctionWindowClosesAt().toString());
        }
        List<EvidenceView> evidence = view.evidence().stream().map(MilestoneDetailResponse::evidence).toList();
        return new MilestoneDetailResponse(view.contractId(), summary, declaration, null, null, evidence, null, null);
    }

    private static EvidenceView evidence(MilestoneEvidenceFile f) {
        return new EvidenceView(f.fileId(), f.uploaderRole().name(), f.scanStatus().name(),
                f.uploadedAt().toString());
    }

    /** A JSON number with no trailing zeros. */
    private static BigDecimal kg(BigDecimal value) {
        return new BigDecimal(SellerWeight.plain(value));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MilestoneResponse(String milestoneId, int milestoneIndex, String status, String fundingStatus,
                                    String fundingScheduledDate, BigDecimal committedQuantity, String batchAmount,
                                    String effectiveUnitPrice, Object acceptedPriceAdjustment,
                                    String expectedDeliveryDate, int graceDays, int fundingDelayBusinessDays,
                                    String effectiveDeliveryDeadline) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SellerDeclarationView(int declarationNumber, BigDecimal sellerDeclaredWeight,
                                        String sellerEvidenceFileId, String deliveryCertificateFileId,
                                        String declaredAt, String correctionWindowClosesAt) {
    }

    public record EvidenceView(String fileId, String uploadedByRole, String status, String uploadedAt) {
    }
}
