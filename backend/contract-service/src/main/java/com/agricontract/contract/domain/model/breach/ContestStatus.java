package com.agricontract.contract.domain.model.breach;

/**
 * Contest window of an AttributionDecision (milestone-escrow design 6.4). NOT_APPLICABLE until RESOLVED; opening the
 * window (OPEN) is #241.
 */
public enum ContestStatus {
    NOT_APPLICABLE,
    OPEN,
    CONTESTED,
    EXPIRED
}
