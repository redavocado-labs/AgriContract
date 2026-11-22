package com.agricontract.contract.domain.model.milestone;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One weigh declaration of the seller (D75): append-only, never overwritten. The first is number 1; each correction
 * inside the window is the next number, with its own consent. The highest number is the one in force.
 */
public record SellerWeighDeclaration(UUID declarationId, String milestoneId, String contractId, int declarationNumber,
                                     BigDecimal declaredWeight, String evidenceFileId,
                                     String deliveryCertificateFileId, UUID consentId, String declaredBy,
                                     Instant declaredAt) {
}
