package com.agricontract.bank.application.usecase;

import com.agricontract.bank.application.dto.LedgerQuery;
import com.agricontract.bank.domain.model.LedgerEntry;

import java.util.List;

public interface QueryLedgerEntriesUseCase {

    /** @throws com.agricontract.bank.application.exception.InvalidLedgerQueryException if the query breaks the contract rules */
    List<LedgerEntry> execute(LedgerQuery query);
}
