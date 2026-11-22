package com.agricontract.contract.infrastructure.web.dto;

import java.util.regex.Pattern;

/** ConsentInput (golden-flow-api.yaml): the operational-tier checkbox (consent design §4), both fields required. */
public record ConsentInput(String statementVersion, String presentedContentHash) {
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    public ConsentInput {
        if (statementVersion == null || statementVersion.isEmpty()) {
            throw new IllegalArgumentException("consent.statementVersion is required");
        }
        if (presentedContentHash == null || !HASH.matcher(presentedContentHash).matches()) {
            throw new IllegalArgumentException("consent.presentedContentHash must be 64 lowercase hex characters");
        }
    }
}
