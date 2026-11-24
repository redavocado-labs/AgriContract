package com.agricontract.escrow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agricontract.escrow.application.port.BankLedgerReader;
import com.agricontract.escrow.application.port.ContractClearanceEventPublisher;
import com.agricontract.escrow.domain.model.ContractClearanceRequest;
import com.agricontract.escrow.domain.model.LedgerEntryView;
import com.agricontract.escrow.domain.repository.ContractClearanceRepository;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The branches of the clearance check the integration test cannot reach on a consistent ledger. */
class CheckContractClearanceUseCaseTest {

    static final Instant REQUESTED = Instant.parse("2026-11-20T00:00:00Z");

    final ContractClearanceRepository clearances = mock(ContractClearanceRepository.class);
    final RemedyLegRepository remedyLegs = mock(RemedyLegRepository.class);
    final BankLedgerReader ledger = mock(BankLedgerReader.class);
    final ContractClearanceEventPublisher events = mock(ContractClearanceEventPublisher.class);
    final CheckContractClearanceUseCase useCase = new CheckContractClearanceUseCase(clearances, remedyLegs, ledger,
            events, Clock.fixed(REQUESTED.plusSeconds(30), ZoneOffset.UTC));

    final String contractId = UUID.randomUUID().toString();
    final ContractClearanceRequest request = new ContractClearanceRequest(contractId, REQUESTED, null);

    @Test
    void onePoolNegative_evenWhenTheSumIsZero_publishesNothing_andMarksChecked() {
        when(remedyLegs.findAcceptedDecisionIds(contractId)).thenReturn(List.of(UUID.randomUUID().toString()));
        when(ledger.entriesOf(contractId)).thenReturn(List.of(
                entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", "100.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", "200.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", "100.00")));
        when(clearances.findForUpdate(contractId)).thenReturn(Optional.of(request));

        CheckContractClearanceUseCase.ClearanceReading reading = useCase.read(request).orElseThrow();
        assertThat(reading.remaining()).isEqualByComparingTo("0.00");
        assertThat(reading.negative()).isTrue();
        useCase.conclude(reading);

        verify(events, never()).contractLockCleared(anyString(), any(), any());
        verify(clearances).markChecked(contractId, REQUESTED, REQUESTED.plusSeconds(30));
    }

    @Test
    void requestedAgainDuringTheCheck_isLeftForTheNextTick() {
        when(clearances.findForUpdate(contractId)).thenReturn(Optional.of(
                new ContractClearanceRequest(contractId, REQUESTED.plusSeconds(5), null)));

        useCase.conclude(new CheckContractClearanceUseCase.ClearanceReading(contractId, REQUESTED, BigDecimal.ZERO,
                false));

        verify(events, never()).contractLockCleared(anyString(), any(), any());
        verify(clearances, never()).markChecked(anyString(), any(), any());
    }

    @Test
    void noAcceptedDecision_marksChecked_withoutReadingTheBank() {
        when(remedyLegs.findAcceptedDecisionIds(contractId)).thenReturn(List.of());

        assertThat(useCase.read(request)).isEmpty();

        verify(ledger, never()).entriesOf(anyString());
        verify(clearances).markChecked(contractId, REQUESTED, REQUESTED.plusSeconds(30));
    }

    private static LedgerEntryView entry(String type, String fund, String amount) {
        return new LedgerEntryView(type, fund, null, new BigDecimal(amount));
    }
}
