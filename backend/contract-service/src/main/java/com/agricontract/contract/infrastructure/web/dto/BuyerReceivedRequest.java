package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.domain.model.milestone.SellerWeight;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * BuyerReceivedRequest (golden-flow-api.yaml, D76): weight with the D72 bounds, the buyer's own uploaded files, the
 * RECORD_BUYER_RECEIPT consent. deliveryCertificateFileId is optional; unknown fields are refused.
 */
public record BuyerReceivedRequest(BigDecimal buyerReceivedWeight, UUID buyerEvidenceFileId,
                                   UUID deliveryCertificateFileId, ConsentInput consent) {
    public BuyerReceivedRequest {
        SellerWeight.requireValid(buyerReceivedWeight);
        if (buyerEvidenceFileId == null) {
            throw new IllegalArgumentException("buyerEvidenceFileId is required");
        }
        if (consent == null) {
            throw new IllegalArgumentException("consent is required");
        }
    }
}
