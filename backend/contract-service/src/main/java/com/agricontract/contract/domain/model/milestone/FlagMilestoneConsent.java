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

/**
 * The buyer's FLAG_MILESTONE_ISSUE consent (D80): operational tier. The server hashes the figures the milestone holds
 * now plus the issueType the buyer chose, so a page showing other weights cannot be ticked.
 */
public final class FlagMilestoneConsent {
    private FlagMilestoneConsent() {
    }

    /**
     * SHA-256 (lowercase hex) of the UTF-8 string
     * {@code FLAG_MILESTONE_ISSUE|milestoneId|declarationNumber|sellerDeclaredWeight|buyerReceivedWeight|issueType}:
     * id lowercase, the declaration number as an integer, both weights with exactly three decimals.
     */
    public static String contentHash(String milestoneId, int declarationNumber, BigDecimal sellerDeclaredWeight,
                                     BigDecimal buyerReceivedWeight, FlagIssueType issueType) {
        return CanonicalTerms.sha256(String.join("|", ConsentActionType.FLAG_MILESTONE_ISSUE.name(),
                milestoneId.toLowerCase(), Integer.toString(declarationNumber), SellerWeight.kg(sellerDeclaredWeight),
                SellerWeight.kg(buyerReceivedWeight), issueType.name()));
    }

    /** The hash of what the milestone holds now. The milestone must be weighed and received. */
    public static String contentHash(Milestone milestone, FlagIssueType issueType) {
        return contentHash(milestone.milestoneId(), milestone.sellerWeighing().declarationCount(),
                milestone.sellerWeighing().declaredWeight(), milestone.buyerReceipt().receivedWeight(), issueType);
    }

    /** 409 CONSENT_STATEMENT_OUTDATED, then 409 CONSENT_CONTENT_MISMATCH; nothing is written on a refusal. */
    public static ConsentEvidence record(Milestone milestone, FlagIssueType issueType, String buyerId,
                                         String statementVersion, String presentedContentHash,
                                         OperationalConsentStatements statements, UUID consentId, Instant consentedAt,
                                         String correlationId) {
        ConsentStatement statement = statements.requireActive(ConsentActionType.FLAG_MILESTONE_ISSUE,
                statementVersion);
        if (!contentHash(milestone, issueType).equals(presentedContentHash)) {
            throw new ContractConflictException(ContractConflictException.CONSENT_CONTENT_MISMATCH,
                    "The flag shown does not match the weights now on this milestone; review them and tick again.");
        }
        return new ConsentEvidence(consentId, ConsentActionType.FLAG_MILESTONE_ISSUE, ConsentSubjectType.MILESTONE,
                milestone.milestoneId(), buyerId, SignerRole.BUYER, statement.statementCode(),
                statement.statementVersion(), statement.statementHash(), presentedContentHash, consentedAt,
                correlationId);
    }
}
