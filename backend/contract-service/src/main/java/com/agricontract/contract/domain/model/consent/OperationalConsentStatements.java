package com.agricontract.contract.domain.model.consent;

import com.agricontract.contract.domain.exception.ContractConflictException;

import java.util.Map;

/**
 * The checkbox statements of the operational tier (consent design §4: checkbox + authenticated session + ownership,
 * no OTP), one fixed sentence per milestone action (milestone-escrow design §3.2.3, D84). Same pattern as
 * SIGN_CONTRACT: the text is a versioned classpath file and statementHash is the SHA-256 of that template exactly,
 * placeholders such as [X] left as written. The figures the actor confirmed are bound by presentedContentHash.
 */
public final class OperationalConsentStatements {
    private final Map<ConsentActionType, ConsentStatement> statements;

    public OperationalConsentStatements(Map<ConsentActionType, ConsentStatement> statements) {
        this.statements = Map.copyOf(statements);
    }

    public static String code(ConsentActionType action, SignerRole role) {
        return action.name() + "_AS_" + role.name();
    }

    /** The statement in force for {@code action}; any other version is CONSENT_STATEMENT_OUTDATED (reload). */
    public ConsentStatement requireActive(ConsentActionType action, String presentedVersion) {
        ConsentStatement active = statements.get(action);
        if (active == null) {
            throw new IllegalStateException("No consent statement is configured for " + action);
        }
        if (!active.statementVersion().equals(presentedVersion)) {
            throw new ContractConflictException(ContractConflictException.CONSENT_STATEMENT_OUTDATED,
                    "Consent statement version " + presentedVersion + " is not in force; current is "
                            + active.statementVersion() + ". Reload the page.");
        }
        return active;
    }
}
