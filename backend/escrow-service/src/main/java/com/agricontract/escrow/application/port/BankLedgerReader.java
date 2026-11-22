package com.agricontract.escrow.application.port;

import com.agricontract.escrow.domain.model.LedgerEntryView;

import java.util.List;

public interface BankLedgerReader {

    /**
     * Every ledger entry of the contract (bank GET /internal/v1/bank/ledger?contractId=). Throws
     * BankLedgerUnavailableException when the bank cannot answer: the caller must not dispatch without it.
     */
    List<LedgerEntryView> entriesOf(String contractId);
}
