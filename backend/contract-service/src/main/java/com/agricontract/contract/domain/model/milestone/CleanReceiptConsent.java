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
 * The buyer's CONFIRM_CLEAN_RECEIPT consent (D77): operational tier, one per milestone. The server hashes the figures
 * the milestone holds now, so a page showing other weights (or an older declaration) cannot be ticked. The consent does
 * not give up the right to dispute or inspection that the contract still allows.
 */
public final class CleanReceiptConsent {
    private CleanReceiptConsent() {
    }

    /**
     * SHA-256 (lowercase hex) of the UTF-8 string
     * {@code CONFIRM_CLEAN_RECEIPT|milestoneId|declarationNumber|sellerDeclaredWeight|buyerReceivedWeight}: id
     * lowercase, the declaration number as an integer without padding, both weights with exactly three decimals.
     */
    public static String contentHash(String milestoneId, int declarationNumber, BigDecimal sellerDeclaredWeight,
                                     BigDecimal buyerReceivedWeight) {
        return CanonicalTerms.sha256(String.join("|", ConsentActionType.CONFIRM_CLEAN_RECEIPT.name(),
                milestoneId.toLowerCase(), Integer.toString(declarationNumber), SellerWeight.kg(sellerDeclaredWeight),
                SellerWeight.kg(buyerReceivedWeight)));
    }

    /** The hash of what the milestone holds now. The milestone must be weighed and received. */
    public static String contentHash(Milestone milestone) {
        return contentHash(milestone.milestoneId(), milestone.sellerWeighing().declarationCount(),
                milestone.sellerWeighing().declaredWeight(), milestone.buyerReceipt().receivedWeight());
    }

    /**
     * Checks the statement version (409 CONSENT_STATEMENT_OUTDATED) and the presented hash (409
     * CONSENT_CONTENT_MISMATCH), then returns the evidence to append. Nothing is written on a refusal.
     */
    public static ConsentEvidence record(Milestone milestone, String buyerId, String statementVersion,
                                         String presentedContentHash, OperationalConsentStatements statements,
                                         UUID consentId, Instant consentedAt, String correlationId) {
        ConsentStatement statement = statements.requireActive(ConsentActionType.CONFIRM_CLEAN_RECEIPT,
                statementVersion);
        if (!contentHash(milestone).equals(presentedContentHash)) {
            throw new ContractConflictException(ContractConflictException.CONSENT_CONTENT_MISMATCH,
                    "The confirmation shown does not match the weights now on this milestone; review them and tick again.");
        }
        return new ConsentEvidence(consentId, ConsentActionType.CONFIRM_CLEAN_RECEIPT, ConsentSubjectType.MILESTONE,
                milestone.milestoneId(), buyerId, SignerRole.BUYER, statement.statementCode(),
                statement.statementVersion(), statement.statementHash(), presentedContentHash, consentedAt,
                correlationId);
    }
}
