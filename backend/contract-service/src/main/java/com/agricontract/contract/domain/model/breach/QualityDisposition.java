package com.agricontract.contract.domain.model.breach;

/** Quality finding of a confirmed Level 1.5 report; null for a QUANTITY flag (D83). */
public enum QualityDisposition {
    CONFORMING,
    PARTIALLY_CONFORMING,
    NON_CONFORMING
}
