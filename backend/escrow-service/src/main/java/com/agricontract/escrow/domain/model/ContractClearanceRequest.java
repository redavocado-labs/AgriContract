package com.agricontract.escrow.domain.model;

import java.time.Instant;

/**
 * A contract_clearance_check row (#609, D93): a remedy or settlement leg of the contract turned COMPLETED at
 * requestedAt; checkedAt is null until the contract was checked after that.
 */
public record ContractClearanceRequest(String contractId, Instant requestedAt, Instant checkedAt) {

    public boolean isChecked() {
        return checkedAt != null;
    }
}
