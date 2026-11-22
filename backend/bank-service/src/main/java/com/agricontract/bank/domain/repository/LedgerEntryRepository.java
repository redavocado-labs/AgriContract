package com.agricontract.bank.domain.repository;

import com.agricontract.bank.domain.model.LedgerEntry;

import java.util.List;
import java.util.Optional;

// Append-only: no update or delete method, on purpose.
public interface LedgerEntryRepository {

    /** @throws com.agricontract.bank.domain.exception.DuplicateLedgerEntryException if the sourceEventId or the remedyLegId exists */
    void append(LedgerEntry entry);

    Optional<LedgerEntry> findBySourceEventId(String sourceEventId);

    Optional<LedgerEntry> findByRemedyLegId(String remedyLegId);

    /** Read-only; ordered by createdAt, entryId. No pagination (the frozen contract has none). */
    List<LedgerEntry> search(LedgerEntryFilter filter);
}
