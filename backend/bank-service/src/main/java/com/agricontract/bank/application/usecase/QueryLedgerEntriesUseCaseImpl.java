package com.agricontract.bank.application.usecase;

import com.agricontract.bank.application.dto.LedgerQuery;
import com.agricontract.bank.application.exception.InvalidLedgerQueryException;
import com.agricontract.bank.domain.model.LedgerEntry;
import com.agricontract.bank.domain.repository.LedgerEntryFilter;
import com.agricontract.bank.domain.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Read-only ledger lookup for internal callers (golden-flow-api getInternalLedgerEntries). */
@Service
@RequiredArgsConstructor
public class QueryLedgerEntriesUseCaseImpl implements QueryLedgerEntriesUseCase {

    private final LedgerEntryRepository ledger;

    @Override
    public List<LedgerEntry> execute(LedgerQuery query) {
        if (query.contractId() == null && query.participantId() == null) {
            throw new InvalidLedgerQueryException("contractId or participantId is required");
        }
        if ((query.from() == null) != (query.to() == null)) {
            throw new InvalidLedgerQueryException("from and to must be supplied together");
        }
        if (query.from() != null && query.from().isAfter(query.to())) {
            throw new InvalidLedgerQueryException("from must not be after to");
        }
        return ledger.search(new LedgerEntryFilter(query.contractId(), query.participantId(),
                query.remedyDecisionId(), query.from(), query.to()));
    }
}
