package com.agricontract.contract.domain.model.consent;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.Contract;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The CONCEDE_DISPUTE and SETTLE_DISPUTE checkbox statements (D57, D92), one per action and role, high-risk tier
 * (checkbox + fresh login + OTP). Same pattern as the operational statements: a versioned classpath file whose hash
 * is the SHA-256 of the template, placeholders ([X], [Y]) left as written. The consent is bound to the contract's
 * signedContentHash, as CANCEL_ACTIVATION is; the milestone and the amount are bound to the OTP challenge.
 */
public final class DisputeExitConsentStatements {
    private final Map<ConsentActionType, Map<SignerRole, ConsentStatement>> statements;

    public DisputeExitConsentStatements(Map<ConsentActionType, Map<SignerRole, ConsentStatement>> statements) {
        Map<ConsentActionType, Map<SignerRole, ConsentStatement>> copy = new EnumMap<>(ConsentActionType.class);
        for (ConsentActionType action : new ConsentActionType[]{ConsentActionType.CONCEDE_DISPUTE,
                ConsentActionType.SETTLE_DISPUTE}) {
            Map<SignerRole, ConsentStatement> byRole = statements.get(action);
            for (SignerRole role : SignerRole.values()) {
                if (byRole == null || !byRole.containsKey(role)) {
                    throw new IllegalArgumentException("Missing " + code(action, role) + " statement");
                }
            }
            copy.put(action, Map.copyOf(byRole));
        }
        this.statements = copy;
    }

    public static String code(ConsentActionType action, SignerRole role) {
        return action.name() + "_AS_" + role.name();
    }

    /**
     * The party's consent to end the dispute: the version in force for (action, role), else CONSENT_STATEMENT_OUTDATED;
     * presentedContentHash must be the contract's signedContentHash, else PRESENTED_CONTENT_HASH_MISMATCH.
     */
    public ConsentEvidence consent(Contract contract, ConsentActionType action, SignerRole role, String actorId,
                                   String presentedVersion, String presentedContentHash, UUID consentId,
                                   Instant now, String correlationId) {
        Map<SignerRole, ConsentStatement> byRole = statements.get(action);
        if (byRole == null) {
            throw new IllegalArgumentException(action + " is not a dispute exit");
        }
        ConsentStatement active = byRole.get(role);
        if (!active.statementVersion().equals(presentedVersion)) {
            throw new ContractConflictException(ContractConflictException.CONSENT_STATEMENT_OUTDATED,
                    "Consent statement version " + presentedVersion + " is not in force; current is "
                            + active.statementVersion() + ". Reload the page.");
        }
        String signed = contract.getCoffeeTerms() == null ? null : contract.getCoffeeTerms().signedContentHash();
        if (!Objects.equals(signed, presentedContentHash)) {
            throw new ContractConflictException(ContractConflictException.PRESENTED_CONTENT_STALE,
                    "The fingerprint shown is not this contract's; reload the page.");
        }
        return new ConsentEvidence(consentId, action, ConsentSubjectType.CONTRACT, contract.getContractId().value(),
                actorId, role, active.statementCode(), active.statementVersion(), active.statementHash(),
                presentedContentHash, now, correlationId);
    }
}
