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
 * has happened. settlement is null until the milestone is SETTLED; flag and breachCaseId are null until the milestone is flagged / contested (MilestoneFlagView).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MilestoneDetailResponse(String contractId, MilestoneResponse milestone,
                                      SellerDeclarationView sellerDeclaration, BuyerReceiptView buyerReceipt, FlagView flag,
                                      List<EvidenceView> evidence, SettlementView settlement, String breachCaseId) {

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
        BuyerReceiptView receipt = null;
        if (view.buyerReceipt() != null) {
            var r = view.buyerReceipt().receipt();
            receipt = new BuyerReceiptView(kg(r.receivedWeight()), r.evidenceFileId(), r.certificateFileId(),
                    r.receivedAt().toString(), view.buyerReceipt().confirmWindowClosesAt().toString());
        }
        List<EvidenceView> evidence = view.evidence().stream().map(MilestoneDetailResponse::evidence).toList();
        SettlementView settlement = null;
        if (m.settlement() != null) {
            var s = m.settlement();
            settlement = new SettlementView(s.path().name(), kg4(s.billableQuantityKg()), s.sellerAmount().wire(),
                    s.buyerRefundAmount().wire(), s.settledAt().toString());
        }
        FlagView flag = null;
        if (view.flag() != null) {
            var f = view.flag().flag();
            flag = new FlagView(f.issueType().name(), f.flaggedAt().toString(),
                    view.flag().sellerResponseWindowClosesAt().toString(),
                    f.contestedAt() == null ? null : f.contestedAt().toString(),
                    f.contestedBy() == null ? null : f.contestedBy().name());
        }
        return new MilestoneDetailResponse(view.contractId(), summary, declaration, receipt, flag, evidence,
                settlement, m.flag() == null ? null : m.flag().breachCaseId());
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

    /** The billable quantity is exact (up to four decimals), a JSON number with no trailing zeros. */
    private static BigDecimal kg4(BigDecimal value) {
        return value.stripTrailingZeros();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FlagView(String issueType, String flaggedAt, String sellerResponseWindowClosesAt,
                           String contestedAt, String contestedBy) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettlementView(String path, BigDecimal billableQuantityKg, String sellerAmount,
                                 String buyerRefundAmount, String settledAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SellerDeclarationView(int declarationNumber, BigDecimal sellerDeclaredWeight,
                                        String sellerEvidenceFileId, String deliveryCertificateFileId,
                                        String declaredAt, String correctionWindowClosesAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BuyerReceiptView(BigDecimal buyerReceivedWeight, String buyerEvidenceFileId,
                                   String deliveryCertificateFileId, String receivedAt,
                                   String confirmWindowClosesAt) {
    }

    public record EvidenceView(String fileId, String uploadedByRole, String status, String uploadedAt) {
    }
}
