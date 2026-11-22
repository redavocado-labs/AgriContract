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
 * The buyer's RECORD_BUYER_RECEIPT consent (D76): operational tier, one consent per receipt. presentedContentHash must
 * equal the server's hash of the receipt, so a page showing other figures or files cannot be ticked.
 */
public final class BuyerReceiptConsent {
    private BuyerReceiptConsent() {
    }

    /**
     * SHA-256 (lowercase hex) of the UTF-8 string
     * {@code RECORD_BUYER_RECEIPT|milestoneId|buyerReceivedWeight|buyerEvidenceFileId|deliveryCertificateFileId},
     * ids lowercase, the weight with exactly three decimals ("9700.000"), no certificate = empty string.
     */
    public static String contentHash(String milestoneId, BigDecimal weight, String evidenceFileId,
                                      String certificateFileId) {
        return CanonicalTerms.sha256(String.join("|", ConsentActionType.RECORD_BUYER_RECEIPT.name(),
                lower(milestoneId), SellerWeight.kg(weight), lower(evidenceFileId), lower(certificateFileId)));
    }

    /**
     * Checks the statement version (409 CONSENT_STATEMENT_OUTDATED) and the presented hash (409
     * CONSENT_CONTENT_MISMATCH), then returns the evidence to append. Nothing is written on a refusal.
     */
    public static ConsentEvidence record(Milestone milestone, String buyerId, BigDecimal weight, String evidenceFileId,
                                         String certificateFileId, String statementVersion,
                                         String presentedContentHash, OperationalConsentStatements statements,
                                         UUID consentId, Instant consentedAt, String correlationId) {
        ConsentStatement statement = statements.requireActive(ConsentActionType.RECORD_BUYER_RECEIPT,
                statementVersion);
        String expected = contentHash(milestone.milestoneId(), weight, evidenceFileId, certificateFileId);
        if (!expected.equals(presentedContentHash)) {
            throw new ContractConflictException(ContractConflictException.CONSENT_CONTENT_MISMATCH,
                    "The confirmation shown does not match this weight and these files; review them and tick again.");
        }
        return new ConsentEvidence(consentId, ConsentActionType.RECORD_BUYER_RECEIPT, ConsentSubjectType.MILESTONE,
                milestone.milestoneId(), buyerId, SignerRole.BUYER, statement.statementCode(),
                statement.statementVersion(), statement.statementHash(), presentedContentHash, consentedAt,
                correlationId);
    }

    private static String lower(String id) {
        return id == null ? "" : id.toLowerCase();
    }
}
