package com.agricontract.contract.domain.model.milestone;

import java.time.Instant;
import java.util.Objects;

/**
 * The buyer's flag on a milestone (D80, D82) and, once the seller contested, the contest. contestedAt, contestedBy and
 * breachCaseId are null until then. A flag is an allegation: it has no money or reputation consequence.
 */
public record MilestoneFlag(FlagIssueType issueType, Instant flaggedAt, Instant contestedAt, ContestedBy contestedBy,
                            String breachCaseId) {
    public MilestoneFlag {
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(flaggedAt, "flaggedAt");
    }

    public boolean contested() {
        return contestedAt != null;
    }
}
