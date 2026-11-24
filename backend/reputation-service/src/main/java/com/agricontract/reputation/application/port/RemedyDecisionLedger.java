package com.agricontract.reputation.application.port;

import com.agricontract.reputation.application.dto.ProcessLockoutCommand;

/** Claims immutable decision and event identities in the caller's transaction. */
public interface RemedyDecisionLedger {
    boolean claim(ProcessLockoutCommand command, String fingerprint);
}
