package com.agricontract.contract.infrastructure.web.dto;

import java.util.regex.Pattern;

/** InitiateDisputeConcessionRequest (golden-flow-api.yaml, D57): every field required, unknown fields refused. */
public record InitiateDisputeConcessionRequest(String consentStatementVersion, String presentedContentHash) {
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    public InitiateDisputeConcessionRequest {
        if (consentStatementVersion == null || consentStatementVersion.isEmpty()) {
            throw new IllegalArgumentException("consentStatementVersion is required");
        }
        if (presentedContentHash == null || !HASH.matcher(presentedContentHash).matches()) {
            throw new IllegalArgumentException("presentedContentHash must be 64 lowercase hex characters");
        }
    }
}
