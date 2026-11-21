package com.agricontract.escrow.application.usecase;

import java.util.List;

// Resends the bank.lock_requested of milestone fundings whose retry is due (#120, D69).
public interface RetryMilestoneFundingUseCase {

    /** Milestones with a retry due now. */
    List<String> milestonesWithRetryDue();

    /** Resends the milestone's lock if its retry is still due; must run in a transaction. True when resent. */
    boolean retryDue(String milestoneId);
}
