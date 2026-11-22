package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.domain.model.milestone.SellerWeight;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SellerWeighedRequest (golden-flow-api.yaml, D71-D75): weight with the D72 bounds, the seller's own uploaded files,
 * the DECLARE_SELLER_WEIGHT consent. deliveryCertificateFileId is optional; unknown fields are refused.
 */
public record SellerWeighedRequest(BigDecimal sellerDeclaredWeight, UUID sellerEvidenceFileId,
                                   UUID deliveryCertificateFileId, ConsentInput consent) {
    public SellerWeighedRequest {
        SellerWeight.requireValid(sellerDeclaredWeight);
        if (sellerEvidenceFileId == null) {
            throw new IllegalArgumentException("sellerEvidenceFileId is required");
        }
        if (consent == null) {
            throw new IllegalArgumentException("consent is required");
        }
    }
}
