package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.exception.BankLedgerUnavailableException;
import com.agricontract.escrow.application.port.BankLedgerReader;
import com.agricontract.escrow.application.port.ContractClearanceEventPublisher;
import com.agricontract.escrow.domain.model.ContractClearanceRequest;
import com.agricontract.escrow.domain.model.FundConservation;
import com.agricontract.escrow.domain.model.FundPool;
import com.agricontract.escrow.domain.repository.ContractClearanceRepository;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * escrow.contract_lock_cleared (#609, D93): a contract may only become SETTLED / TERMINATED once every leg of its
 * remedy decisions went through and the money the bank still holds for it is 0.00. Run per contract_clearance_check
 * row a COMPLETED remedy or settlement leg requested:
 * <ol>
 *   <li>{@link #read}, no transaction: no ACCEPTED decision, or a remedy leg still SENT / FAILED, marks the row
 *       checked (the next completion requests again). Otherwise the bank ledger is read; a bank that cannot answer
 *       leaves the row unchecked for the next tick.</li>
 *   <li>{@link #conclude}, in the caller's transaction under the row lock: a row requested again meanwhile is left for
 *       the next tick; a leg in flight again marks it checked; else remaining 0.00 publishes the event, more marks
 *       it checked (INFO), less (the ledger moved more than it locked) marks it checked with an ERROR and no event.</li>
 * </ol>
 * The ledger is read outside any transaction so no row lock waits on the bank.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckContractClearanceUseCase {

    private static final int BATCH = 50;

    private final ContractClearanceRepository clearances;
    private final RemedyLegRepository remedyLegs;
    private final BankLedgerReader ledger;
    private final ContractClearanceEventPublisher events;
    private final Clock clock;

    /** What the ledger said about one requested contract, to be concluded under the row lock. */
    public record ClearanceReading(String contractId, Instant requestedAt, BigDecimal remaining, boolean negative) {
    }

    public List<ContractClearanceRequest> requested() {
        return clearances.findUnchecked(BATCH);
    }

    /** Steps 1-3; empty when the row is done for this tick (marked checked, or the ledger is unavailable). */
    public Optional<ClearanceReading> read(ContractClearanceRequest request) {
        String contractId = request.contractId();
        if (remedyLegs.findAcceptedDecisionIds(contractId).isEmpty()) {
            clearances.markChecked(contractId, request.requestedAt(), clock.instant());
            log.debug("event=contract_clearance_no_decision contractId={}", contractId);
            return Optional.empty();
        }
        if (remedyLegs.hasLegInFlight(contractId)) {
            clearances.markChecked(contractId, request.requestedAt(), clock.instant());
            log.info("event=contract_clearance_leg_in_flight contractId={}", contractId);
            return Optional.empty();
        }
        Map<FundPool, BigDecimal> pools;
        try {
            pools = FundConservation.remainingByPool(ledger.entriesOf(contractId));
        } catch (BankLedgerUnavailableException e) {
            log.warn("event=contract_clearance_ledger_unavailable contractId={}: {}", contractId, e.getMessage());
            return Optional.empty();
        }
        BigDecimal remaining = pools.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean negative = pools.values().stream().anyMatch(amount -> amount.signum() < 0);
        return Optional.of(new ClearanceReading(contractId, request.requestedAt(), remaining, negative));
    }

    /** Step 4, in the caller's transaction. */
    public void conclude(ClearanceReading reading) {
        String contractId = reading.contractId();
        Optional<ContractClearanceRequest> row = clearances.findForUpdate(contractId);
        if (row.isEmpty() || row.get().isChecked() || !row.get().requestedAt().equals(reading.requestedAt())) {
            log.debug("event=contract_clearance_requested_again contractId={}", contractId);
            return;
        }
        Instant now = clock.instant();
        if (remedyLegs.hasLegInFlight(contractId)) {
            clearances.markChecked(contractId, reading.requestedAt(), now);
            log.info("event=contract_clearance_leg_in_flight contractId={}", contractId);
            return;
        }
        if (reading.negative()) {
            clearances.markChecked(contractId, reading.requestedAt(), now);
            log.error("event=contract_clearance_ledger_negative contractId={} remaining={}: a pool moved more than it"
                    + " locked, nothing published, OPERATOR must look at the ledger", contractId,
                    reading.remaining().toPlainString());
            return;
        }
        if (reading.remaining().signum() > 0) {
            clearances.markChecked(contractId, reading.requestedAt(), now);
            log.info("event=contract_clearance_lock_remaining contractId={} remaining={}", contractId,
                    reading.remaining().toPlainString());
            return;
        }
        List<String> decisionIds = remedyLegs.findAcceptedDecisionIds(contractId);
        events.contractLockCleared(contractId, decisionIds, now);
        clearances.markChecked(contractId, reading.requestedAt(), now);
        log.info("event=contract_lock_cleared contractId={} remedyDecisionIds={}", contractId, decisionIds);
    }
}
