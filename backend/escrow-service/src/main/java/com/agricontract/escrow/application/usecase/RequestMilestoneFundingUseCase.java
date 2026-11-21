package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.MilestoneFundingRequestedCommand;

// milestone.funding_requested → lock the batch amount from the buyer (#120, D63/D64). Runs in the caller's transaction.
public interface RequestMilestoneFundingUseCase {

    void execute(MilestoneFundingRequestedCommand command);
}
