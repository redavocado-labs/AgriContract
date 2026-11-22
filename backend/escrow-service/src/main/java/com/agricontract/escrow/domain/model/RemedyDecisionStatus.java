package com.agricontract.escrow.domain.model;

/** ACCEPTED: its legs were written with their bank commands. REJECTED: nothing was written, only the reason (#249). */
public enum RemedyDecisionStatus {
    ACCEPTED,
    REJECTED
}
