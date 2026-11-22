package com.agricontract.contract.domain.model.milestone;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The seller's declaration in force on a milestone (D72-D75): the latest declared weight and files, when the first
 * declaration was made (the correction window runs from it, D75) and how many declarations there were. Every
 * declaration is also kept on its own in {@link SellerWeighDeclaration}.
 */
public record SellerWeighing(BigDecimal declaredWeight, String evidenceFileId, String deliveryCertificateFileId,
                             Instant firstDeclaredAt, int declarationCount) {
    public SellerWeighing {
        SellerWeight.requireValid(declaredWeight);
        Objects.requireNonNull(evidenceFileId, "evidenceFileId");
        Objects.requireNonNull(firstDeclaredAt, "firstDeclaredAt");
        if (declarationCount < 1) {
            throw new IllegalArgumentException("declarationCount must be at least 1");
        }
    }
}
