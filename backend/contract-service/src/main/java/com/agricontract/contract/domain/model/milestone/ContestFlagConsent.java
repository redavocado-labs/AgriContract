package com.agricontract.contract.domain.model.milestone;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.consent.ConsentActionType;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.ConsentStatement;
import com.agricontract.contract.domain.model.consent.ConsentSubjectType;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The seller's CONTEST_MILESTONE_FLAG consent (D80): operational tier. Everything hashed comes from the milestone. */
public final class ContestFlagConsent {
    private ContestFlagConsent() {
    }

    /**
     * SHA-256 (lowercase hex) of the UTF-8 string
     * {@code CONTEST_MILESTONE_FLAG|milestoneId|sellerDeclaredWeight|buyerReceivedWeight|issueType}: id lowercase,
     * both weights with exactly three decimals.
     */
    public static String contentHash(String milestoneId, BigDecimal sellerDeclaredWeight,
                                     BigDecimal buyerReceivedWeight, FlagIssueType issueType) {
        return CanonicalTerms.sha256(String.join("|", ConsentActionType.CONTEST_MILESTONE_FLAG.name(),
                milestoneId.toLowerCase(), SellerWeight.kg(sellerDeclaredWeight), SellerWeight.kg(buyerReceivedWeight),
                issueType.name()));
    }

    /** The hash of what the milestone holds now. The milestone must be flagged. */
    public static String contentHash(Milestone milestone) {
        return contentHash(milestone.milestoneId(), milestone.sellerWeighing().declaredWeight(),
                milestone.buyerReceipt().receivedWeight(), milestone.flag().issueType());
    }

    /** 409 CONSENT_STATEMENT_OUTDATED, then 409 CONSENT_CONTENT_MISMATCH; nothing is written on a refusal. */
    public static ConsentEvidence record(Milestone milestone, String sellerId, String statementVersion,
                                         String presentedContentHash, OperationalConsentStatements statements,
                                         UUID consentId, Instant consentedAt, String correlationId) {
        ConsentStatement statement = statements.requireActive(ConsentActionType.CONTEST_MILESTONE_FLAG,
                statementVersion);
        if (!contentHash(milestone).equals(presentedContentHash)) {
            throw new ContractConflictException(ContractConflictException.CONSENT_CONTENT_MISMATCH,
                    "The contest shown does not match the flag now on this milestone; review it and tick again.");
        }
        return new ConsentEvidence(consentId, ConsentActionType.CONTEST_MILESTONE_FLAG, ConsentSubjectType.MILESTONE,
                milestone.milestoneId(), sellerId, SignerRole.SELLER, statement.statementCode(),
                statement.statementVersion(), statement.statementHash(), presentedContentHash, consentedAt,
                correlationId);
    }
}
