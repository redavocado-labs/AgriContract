package com.agricontract.bank.application.usecase;

import com.agricontract.bank.application.port.TransactionRunner;
import com.agricontract.bank.application.dto.LedgerCommand;
import com.agricontract.bank.application.port.LedgerResultPublisher;
import com.agricontract.bank.domain.exception.DuplicateLedgerEntryException;
import com.agricontract.bank.domain.model.EntryType;
import com.agricontract.bank.domain.model.FundType;
import com.agricontract.bank.domain.model.LedgerEntry;
import com.agricontract.bank.domain.model.Money;
import com.agricontract.bank.domain.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.function.Supplier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessLedgerCommandUseCaseImplTest {

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final RecordingResults results = new RecordingResults();
    private final ProcessLedgerCommandUseCaseImpl useCase = new ProcessLedgerCommandUseCaseImpl(ledger, results,
            new DirectTransactionRunner(),
            Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneOffset.UTC));

    @Test
    void firstRequestRecordsOneEntryAndConfirmsIt() {
        LedgerCommand command = buyerDeposit("40000000.00");
        useCase.execute(command);

        assertThat(ledger.entries).hasSize(1);
        assertThat(results.completed).containsExactly(ledger.entries.get(command.sourceEventId()).getEntryId());
        assertThat(results.failed).isEmpty();
    }

    @Test
    void repeatedRequestWritesNothingAndReconfirmsTheSameEntry() {
        LedgerCommand command = buyerDeposit("40000000.00");
        useCase.execute(command);
        useCase.execute(command);

        assertThat(ledger.appends).isEqualTo(1);
        String entryId = ledger.entries.get(command.sourceEventId()).getEntryId();
        assertThat(results.completed).containsExactly(entryId, entryId);
    }

    @Test
    void sameSourceEventWithDifferentContentIsNotConfirmed() {
        LedgerCommand command = buyerDeposit("40000000.00");
        useCase.execute(command);
        useCase.execute(withAmount(command, "50000000.00"));

        assertThat(ledger.appends).isEqualTo(1);
        assertThat(results.completed).hasSize(1);
        assertThat(results.failed).isEmpty();
    }

    @Test
    void ledgerRuleViolationIsAnsweredWithLockFailed() {
        // A milestone lock without its milestone: readable, but not a valid ledger row.
        String id = id();
        LedgerCommand command = new LedgerCommand(id, id(), id(), null, id(), EntryType.LOCK_MILESTONE,
                FundType.MILESTONE_PAYMENT, null, null, new BigDecimal("200000000.00"), id);
        useCase.execute(command);

        assertThat(ledger.entries).isEmpty();
        assertThat(results.failed).singleElement().asString().contains("needs a milestoneId");
    }

    @Test
    void losingAnInsertRaceReconfirmsTheWinnersEntry() {
        LedgerCommand command = buyerDeposit("40000000.00");
        ledger.raceWinner = LedgerEntry.reconstitute(id(), command.sourceEventId(), command.contractId(), null,
                command.userId(), command.entryType(), command.fundType(), null, null,
                new Money(command.amount()), Instant.now());

        useCase.execute(command);

        assertThat(results.completed).containsExactly(ledger.raceWinner.getEntryId());
    }

    @Test
    void seizeRecordsOneEntryAndConfirmsIt() {
        LedgerCommand command = seize(EntryType.CONTRACTUAL_PENALTY, FundType.MILESTONE_PAYMENT, id(), id());
        useCase.execute(command);

        assertThat(ledger.entries).hasSize(1);
        assertThat(results.completed).containsExactly(ledger.entries.get(command.sourceEventId()).getEntryId());
        assertThat(results.failed).isEmpty();
    }

    @Test
    void seizeReplayWithTheSameSourceEventReconfirms() {
        LedgerCommand command = seize(EntryType.DEPOSIT_FORFEITURE, FundType.BUYER_DEPOSIT, null, id());
        useCase.execute(command);
        useCase.execute(command);

        assertThat(ledger.appends).isEqualTo(1);
        assertThat(results.completed).hasSize(2);
    }

    @Test
    void sameRemedyLegUnderAnotherSourceEventWritesAndSendsNothing() {
        String leg = id();
        useCase.execute(seize(EntryType.DAMAGES_COMPENSATION, FundType.MILESTONE_PAYMENT, id(), leg));
        useCase.execute(seize(EntryType.DAMAGES_COMPENSATION, FundType.MILESTONE_PAYMENT, id(), leg));

        assertThat(ledger.appends).isEqualTo(1);
        assertThat(results.completed).hasSize(1);
        assertThat(results.failed).isEmpty();
    }

    @Test
    void losingTheRemedyLegRaceSendsNothing() {
        LedgerCommand first = seize(EntryType.CONTRACTUAL_PENALTY, FundType.MILESTONE_PAYMENT, id(), id());
        LedgerCommand second = seize(EntryType.CONTRACTUAL_PENALTY, FundType.MILESTONE_PAYMENT, id(), first.remedyLegId());
        ledger.legRaceWinner = LedgerEntry.reconstitute(id(), first.sourceEventId(), first.contractId(),
                first.milestoneId(), first.userId(), first.entryType(), first.fundType(), first.remedyDecisionId(),
                first.remedyLegId(), new Money(first.amount()), Instant.now());

        useCase.execute(second);

        assertThat(results.completed).isEmpty();
        assertThat(results.failed).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "CONTRACTUAL_PENALTY, BUYER_DEPOSIT, false",
            "DAMAGES_COMPENSATION, SELLER_DEPOSIT, false",
            "DEPOSIT_FORFEITURE, MILESTONE_PAYMENT, true",
            "CONTRACTUAL_PENALTY, MILESTONE_PAYMENT, false"})
    void seizeBreakingAFundOrScopeRuleIsAnsweredWithFailed(EntryType type, FundType fund, boolean withMilestone) {
        // The last row has a milestone-level fund but no milestoneId.
        LedgerCommand command = seize(type, fund, withMilestone ? id() : null, id());
        useCase.execute(command);

        assertThat(ledger.entries).isEmpty();
        assertThat(results.failed).hasSize(1);
        assertThat(results.completed).isEmpty();
    }

    private static LedgerCommand seize(EntryType type, FundType fund, String milestoneId, String legId) {
        String eventId = id();
        return new LedgerCommand(eventId, id(), id(), milestoneId, id(), type, fund, id(), legId,
                new BigDecimal("1500000.00"), eventId);
    }

    private static LedgerCommand buyerDeposit(String amount) {
        String eventId = id();
        return new LedgerCommand(eventId, id(), id(), null, id(), EntryType.LOCK_BUYER_DEPOSIT,
                FundType.BUYER_DEPOSIT, null, null, new BigDecimal(amount), eventId);
    }

    private static LedgerCommand withAmount(LedgerCommand c, String amount) {
        return new LedgerCommand(c.requestEventId(), c.correlationId(), c.contractId(), c.milestoneId(),
                c.userId(), c.entryType(), c.fundType(), c.remedyDecisionId(), c.remedyLegId(),
                new BigDecimal(amount), c.sourceEventId());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static final class InMemoryLedger implements LedgerEntryRepository {
        final Map<String, LedgerEntry> entries = new HashMap<>();
        int appends;
        // Set to simulate a concurrent delivery committing between our lookup and our insert.
        LedgerEntry raceWinner;
        // Same, but the constraint that fires is remedy_leg_id (the winner has another sourceEventId).
        LedgerEntry legRaceWinner;

        @Override
        public void append(LedgerEntry entry) {
            if (raceWinner != null) {
                entries.put(raceWinner.getSourceEventId(), raceWinner);
                throw new DuplicateLedgerEntryException(entry.getSourceEventId());
            }
            if (legRaceWinner != null) {
                entries.put(legRaceWinner.getSourceEventId(), legRaceWinner);
                throw DuplicateLedgerEntryException.forRemedyLeg(entry.getRemedyLegId());
            }
            if (entries.containsKey(entry.getSourceEventId())) {
                throw new DuplicateLedgerEntryException(entry.getSourceEventId());
            }
            appends++;
            entries.put(entry.getSourceEventId(), entry);
        }

        @Override
        public List<LedgerEntry> search(com.agricontract.bank.domain.repository.LedgerEntryFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LedgerEntry> findBySourceEventId(String sourceEventId) {
            return Optional.ofNullable(entries.get(sourceEventId));
        }

        @Override
        public Optional<LedgerEntry> findByRemedyLegId(String remedyLegId) {
            return entries.values().stream().filter(e -> remedyLegId.equals(e.getRemedyLegId())).findFirst();
        }
    }

    private static final class RecordingResults implements LedgerResultPublisher {
        final List<String> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        @Override
        public void completed(LedgerCommand command, LedgerEntry entry) {
            completed.add(entry.getEntryId());
        }

        @Override
        public void failed(LedgerCommand command, String reason) {
            failed.add(reason);
        }
    }

    /** Atomicity is proven on MySQL in LedgerCommandFlowIntegrationTest; here the work just runs. */
    private static final class DirectTransactionRunner implements TransactionRunner {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
