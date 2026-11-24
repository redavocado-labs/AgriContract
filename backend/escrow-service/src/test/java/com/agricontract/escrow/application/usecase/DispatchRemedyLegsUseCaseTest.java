package com.agricontract.escrow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agricontract.escrow.application.dto.RemedyFinalizedCommand;
import com.agricontract.escrow.application.exception.BankLedgerUnavailableException;
import com.agricontract.escrow.application.port.BankLedgerReader;
import com.agricontract.escrow.application.port.OperatorAlertPublisher;
import com.agricontract.escrow.application.port.RemedyCommandPublisher;
import com.agricontract.escrow.domain.model.FundPool;
import com.agricontract.escrow.domain.model.LedgerEntryView;
import com.agricontract.escrow.domain.model.PoolAmount;
import com.agricontract.escrow.domain.model.RemedyDecision;
import com.agricontract.escrow.domain.model.RemedyDecisionStatus;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.escrow.domain.model.RemedyLegInput;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.repository.DepositLegRepository;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import com.agricontract.escrow.domain.repository.OutstandingCommandRepository;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** The vectors of #249 through the use case: what is written, and what is not. */
class DispatchRemedyLegsUseCaseTest {

    final RemedyLegRepository repo = mock(RemedyLegRepository.class);
    final DepositLegRepository depositLegs = mock(DepositLegRepository.class);
    final EscrowMilestoneRepository milestones = mock(EscrowMilestoneRepository.class);
    final SettlementLegRepository settlementLegs = mock(SettlementLegRepository.class);
    final OutstandingCommandRepository outstanding = mock(OutstandingCommandRepository.class);
    final BankLedgerReader ledger = mock(BankLedgerReader.class);
    final RemedyCommandPublisher commands = mock(RemedyCommandPublisher.class);
    final OperatorAlertPublisher alerts = mock(OperatorAlertPublisher.class);
    final DispatchRemedyLegsUseCase useCase = new DispatchRemedyLegsUseCase(repo, depositLegs, milestones, settlementLegs, outstanding, ledger, commands, alerts,
            Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));

    final String contractId = UUID.randomUUID().toString();
    final String buyerId = UUID.randomUUID().toString();
    final String sellerId = UUID.randomUUID().toString();
    final String milestone = UUID.randomUUID().toString();

    @Test
    void normalCompletion_acceptsAndSendsOneRefundAndOneRelease() {
        stubLedger(deposits());

        useCase.execute(command(List.of(), leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00"),
                leg("PAYMENT_SETTLEMENT", "SELLER_DEPOSIT", "SELLER", "5000000.00")));

        ArgumentCaptor<RemedyDecision> decision = ArgumentCaptor.forClass(RemedyDecision.class);
        verify(repo).insertDecision(decision.capture());
        assertThat(decision.getValue().status()).isEqualTo(RemedyDecisionStatus.ACCEPTED);
        ArgumentCaptor<RemedyLeg> legs = ArgumentCaptor.forClass(RemedyLeg.class);
        verify(commands, times(2)).requested(legs.capture(), anyString(), anyString());
        assertThat(legs.getAllValues()).extracting(l -> l.getEntryType().name())
                .containsExactly("REFUND_TO_BUYER", "RELEASE_TO_SELLER");
        assertThat(legs.getAllValues()).extracting(RemedyLeg::getUserId).containsExactly(buyerId, sellerId);
        verify(repo, times(2)).insert(any());
    }

    @Test
    void oneCentOverTheLock_rejectsAndWritesNothingElse() {
        stubLedger(deposits());

        useCase.execute(command(List.of(), leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.01"),
                leg("PAYMENT_SETTLEMENT", "SELLER_DEPOSIT", "SELLER", "5000000.00")));

        ArgumentCaptor<RemedyDecision> decision = ArgumentCaptor.forClass(RemedyDecision.class);
        verify(repo).insertDecision(decision.capture());
        assertThat(decision.getValue().status()).isEqualTo(RemedyDecisionStatus.REJECTED);
        assertThat(decision.getValue().rejectReason()).contains("exceed");
        verify(repo, never()).insert(any());
        verify(commands, never()).requested(any(), anyString(), anyString());
    }

    @Test
    void milestoneBatchMovedWhole_isAccepted_andOtherwiseRejected() {
        stubLedger(List.of(new LedgerEntryView("LOCK_MILESTONE", "MILESTONE_PAYMENT", milestone,
                new BigDecimal("500000000.00"))));

        useCase.execute(command(List.of(milestone), leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "487500000.00"),
                leg("PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER", "12500000.00")));
        verify(commands, times(2)).requested(any(), anyString(), anyString());
    }

    @Test
    void milestoneBatchNotMovedWhole_isRejected() {
        stubLedger(List.of(new LedgerEntryView("LOCK_MILESTONE", "MILESTONE_PAYMENT", milestone,
                new BigDecimal("500000000.00"))));

        useCase.execute(command(List.of(milestone), leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "487500000.00"),
                leg("PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER", "12499999.00")));

        verify(commands, never()).requested(any(), anyString(), anyString());
        verify(repo, never()).insert(any());
    }

    @Test
    void settlementLegInFlight_reducesAvailable() {
        stubLedger(List.of(new LedgerEntryView("LOCK_MILESTONE", "MILESTONE_PAYMENT", milestone,
                new BigDecimal("500000000.00"))));
        when(outstanding.inFlight(contractId)).thenReturn(List.of(
                new PoolAmount(FundPool.of("MILESTONE_PAYMENT", milestone), new BigDecimal("100.00"))));

        useCase.execute(command(List.of(milestone), leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "487500000.00"),
                leg("PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER", "12500000.00")));

        verify(commands, never()).requested(any(), anyString(), anyString());
    }

    @Test
    void invalidMapping_rejectsWithoutReadingTheBank() {
        useCase.execute(command(List.of(), leg("PAYMENT_REFUND", "SELLER_DEPOSIT", "BUYER", "1.00")));

        verify(ledger, never()).entriesOf(anyString());
        verify(commands, never()).requested(any(), anyString(), anyString());
        ArgumentCaptor<RemedyDecision> decision = ArgumentCaptor.forClass(RemedyDecision.class);
        verify(repo).insertDecision(decision.capture());
        assertThat(decision.getValue().status()).isEqualTo(RemedyDecisionStatus.REJECTED);
    }

    @Test
    void bankUnavailable_throws_andWritesNothing() {
        when(ledger.entriesOf(contractId)).thenThrow(new BankLedgerUnavailableException("503", null));

        assertThatThrownBy(() -> useCase.execute(command(List.of(),
                leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "1.00"))))
                .isInstanceOf(BankLedgerUnavailableException.class);
        verify(repo, never()).insertDecision(any());
        verify(commands, never()).requested(any(), anyString(), anyString());
    }

    @Test
    void knownDecision_sendsNothing() {
        when(repo.findDecisionForUpdate(anyString())).thenReturn(Optional.of(
                RemedyDecision.accepted("d", contractId, "e", Instant.now())));
        when(repo.findByDecisionId(anyString())).thenReturn(List.of());

        useCase.execute(command(List.of(), leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "1.00")));

        verify(ledger, never()).entriesOf(anyString());
        verify(repo, never()).insertDecision(any());
        verify(commands, never()).requested(any(), anyString(), anyString());
    }

    @Test
    void zeroAndNoneLegs_acceptWithoutReadingTheBank() {
        useCase.execute(command(List.of(), leg("NONE", "BUYER_DEPOSIT", "BUYER", "0.00"),
                leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "0.00")));

        verify(ledger, never()).entriesOf(anyString());
        verify(commands, never()).requested(any(), anyString(), anyString());
        ArgumentCaptor<RemedyDecision> decision = ArgumentCaptor.forClass(RemedyDecision.class);
        verify(repo).insertDecision(decision.capture());
        assertThat(decision.getValue().status()).isEqualTo(RemedyDecisionStatus.ACCEPTED);
    }

    private List<LedgerEntryView> deposits() {
        return List.of(
                new LedgerEntryView("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, new BigDecimal("10000000.00")),
                new LedgerEntryView("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, new BigDecimal("5000000.00")));
    }

    private void stubLedger(List<LedgerEntryView> entries) {
        when(ledger.entriesOf(contractId)).thenReturn(entries);
        when(outstanding.inFlight(contractId)).thenReturn(List.of());
        when(repo.findDecisionForUpdate(anyString())).thenReturn(Optional.empty());
        when(milestones.findByIdForUpdate(anyString())).thenReturn(Optional.of(mock(EscrowMilestone.class)));
    }

    private RemedyFinalizedCommand command(List<String> milestones, RemedyLegInput... legs) {
        return new RemedyFinalizedCommand(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), contractId, buyerId, sellerId, milestones, List.of(legs));
    }

    private static RemedyLegInput leg(String type, String fund, String role, String amount) {
        return new RemedyLegInput(UUID.randomUUID().toString(), type, fund, role, new BigDecimal(amount));
    }

    @Test
    void locksComeBeforeTheLedgerRead_depositsFirst_thenTheDecision_thenTheMilestoneAndItsSettlementLegs() {
        stubLedger(List.of(
                new LedgerEntryView("LOCK_MILESTONE", "MILESTONE_PAYMENT", milestone, new BigDecimal("10.00")),
                new LedgerEntryView("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, new BigDecimal("1.00"))));

        useCase.execute(command(List.of(milestone), leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "10.00"),
                leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "0.01")));

        InOrder order = Mockito.inOrder(depositLegs, repo, milestones, settlementLegs, ledger, outstanding);
        order.verify(depositLegs).findByContractIdForUpdate(contractId);
        order.verify(repo).findDecisionForUpdate(anyString());
        order.verify(milestones).findByIdForUpdate(milestone);
        order.verify(settlementLegs).findByMilestoneIdForUpdate(milestone);
        order.verify(ledger).entriesOf(contractId);
        order.verify(outstanding).inFlight(contractId);
    }

    @Test
    void milestoneUnknownToEscrow_isRejected_withoutReadingTheBank() {
        when(repo.findDecisionForUpdate(anyString())).thenReturn(Optional.empty());
        when(milestones.findByIdForUpdate(anyString())).thenReturn(Optional.empty());

        useCase.execute(command(List.of(milestone), leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "10.00")));

        verify(ledger, never()).entriesOf(anyString());
        verify(commands, never()).requested(any(), anyString(), anyString());
        ArgumentCaptor<RemedyDecision> decision = ArgumentCaptor.forClass(RemedyDecision.class);
        verify(repo).insertDecision(decision.capture());
        assertThat(decision.getValue().status()).isEqualTo(RemedyDecisionStatus.REJECTED);
    }
}
