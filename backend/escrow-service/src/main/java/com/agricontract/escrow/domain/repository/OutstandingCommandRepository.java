package com.agricontract.escrow.domain.repository;

import com.agricontract.escrow.domain.model.PoolAmount;

import java.util.List;

public interface OutstandingCommandRepository {

    /**
     * What escrow has sent to the bank for this contract with no bank result yet, per pool: settlement legs
     * (REQUESTED, FAILED), deposit returns (RETURN_REQUESTED, RETURN_FAILED) and remedy legs (SENT, FAILED). A
     * refused leg is counted too: it is resent, so its money is still claimed.
     */
    List<PoolAmount> inFlight(String contractId);
}
