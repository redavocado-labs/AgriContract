package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.LockResultCommand;

// bank.lock_completed / bank.lock_failed of a milestone funding (#120, D69). Runs in the caller's transaction.
public interface RecordMilestoneFundingResultUseCase {

    void execute(LockResultCommand command);
}
