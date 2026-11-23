package com.agricontract.contract.infrastructure.web.dto;

import java.util.regex.Pattern;

/** InitiateDisputeSettlementRequest (golden-flow-api.yaml, D57): every field required, unknown fields refused. */
public record InitiateDisputeSettlementRequest(String agreedSellerEntitlementAmount, String consentStatementVersion,
                                               String presentedContentHash) {
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern AMOUNT = Pattern.compile("^[0-9]+\\.[0-9]{2}$");

    public InitiateDisputeSettlementRequest {
        if (agreedSellerEntitlementAmount == null || agreedSellerEntitlementAmount.length() > 19
                || !AMOUNT.matcher(agreedSellerEntitlementAmount).matches()) {
            throw new IllegalArgumentException("agreedSellerEntitlementAmount must look like 12345.00");
        }
        if (consentStatementVersion == null || consentStatementVersion.isEmpty()) {
            throw new IllegalArgumentException("consentStatementVersion is required");
        }
        if (presentedContentHash == null || !HASH.matcher(presentedContentHash).matches()) {
            throw new IllegalArgumentException("presentedContentHash must be 64 lowercase hex characters");
        }
    }
}
