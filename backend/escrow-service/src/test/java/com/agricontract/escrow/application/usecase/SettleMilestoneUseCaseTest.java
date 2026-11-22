package com.agricontract.escrow.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agricontract.escrow.application.dto.MilestoneSettledCommand;
import com.agricontract.escrow.application.port.SettlementCommandPublisher;
import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import com.agricontract.escrow.domain.model.DepositLeg;
import com.agricontract.escrow.domain.model.DepositLegParty;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.SettlementLeg;
import com.agricontract.escrow.domain.repository.DepositLegRepository;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** milestone.settled repeats, conflicts and the seller cross-check (#248). */
class SettleMilestoneUseCaseTest {

    static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    static final String CONTRACT = UUID.randomUUID().toString();
    static final String BUYER = UUID.randomUUID().toString();
    static final String SELLER = UUID.randomUUID().toString();

    private final EscrowMilestoneRepository milestones = mock(EscrowMilestoneRepository.class);
    private final SettlementLegRepository settlementLegs = mock(SettlementLegRepository.class);
    private final DepositLegRepository depositLegs = mock(DepositLegRepository.class);
    private final SettlementCommandPublisher bankCommands = mock(SettlementCommandPublisher.class);
    private final SettleMilestoneUseCase useCase = new SettleMilestoneUseCase(milestones, settlementLegs,
            depositLegs, bankCommands, Clock.fixed(NOW, ZoneOffset.UTC));
    private EscrowMilestone milestone;

    @BeforeEach
    void lockedMilestone() {
        milestone = EscrowMilestone.requestFunding(UUID.randomUUID().toString(), CONTRACT, 1, BUYER,
                money("300000000.00"), NOW.minusSeconds(60), UUID.randomUUID().toString(), NOW.minusSeconds(60));
        milestone.markLocked(UUID.randomUUID().toString(), NOW.minusSeconds(30), NOW.minusSeconds(30));
        when(milestones.findByIdForUpdate(milestone.getMilestoneId())).thenReturn(Optional.of(milestone));
        when(depositLegs.findByContractId(CONTRACT)).thenReturn(List.of());
    }

    @Test
    void exactRepeat_isAcked_andSendsNothing() {
        List<SettlementLeg> legs = startSettlement("292500000.00", SELLER);

        useCase.execute(command("292500000.00", SELLER));

        verify(settlementLegs, times(legs.size())).insert(any());
        verify(bankCommands, times(legs.size())).settlementLegRequested(any(), anyString(), anyString());
    }

    @Test
    void conflictingRepeat_otherAmountOrSeller_isRefusedToTheDlq() {
        startSettlement("292500000.00", SELLER);

        assertThatThrownBy(() -> useCase.execute(command("300000000.00", SELLER)))
                .isInstanceOf(InvalidDepositLegException.class);
        assertThatThrownBy(() -> useCase.execute(command("292500000.00", UUID.randomUUID().toString())))
                .isInstanceOf(InvalidDepositLegException.class);
        verify(bankCommands, times(2)).settlementLegRequested(any(), anyString(), anyString());
    }

    @Test
    void sellerOtherThanTheSellerDepositOwner_isRefused() {
        DepositLeg sellerDeposit = DepositLeg.request(CONTRACT, DepositLegParty.SELLER, SELLER,
                money("10000000.00"), NOW);
        when(depositLegs.findByContractId(CONTRACT)).thenReturn(List.of(sellerDeposit));

        assertThatThrownBy(() -> useCase.execute(command("292500000.00", UUID.randomUUID().toString())))
                .isInstanceOf(InvalidDepositLegException.class);
        verify(settlementLegs, never()).insert(any());
        verify(bankCommands, never()).settlementLegRequested(any(), anyString(), anyString());
    }

    @Test
    void sellerMatchingTheSellerDeposit_settles() {
        DepositLeg sellerDeposit = DepositLeg.request(CONTRACT, DepositLegParty.SELLER, SELLER,
                money("10000000.00"), NOW);
        when(depositLegs.findByContractId(CONTRACT)).thenReturn(List.of(sellerDeposit));

        useCase.execute(command("292500000.00", SELLER));

        verify(bankCommands, times(2)).settlementLegRequested(any(), anyString(), anyString());
    }

    /** Runs the first settlement through the use case, then serves its legs back like the repository would. */
    private List<SettlementLeg> startSettlement(String actual, String seller) {
        org.mockito.ArgumentCaptor<SettlementLeg> inserted = org.mockito.ArgumentCaptor.forClass(SettlementLeg.class);
        useCase.execute(command(actual, seller));
        verify(settlementLegs, org.mockito.Mockito.atLeastOnce()).insert(inserted.capture());
        List<SettlementLeg> legs = inserted.getAllValues();
        when(settlementLegs.findByMilestoneIdForUpdate(milestone.getMilestoneId())).thenReturn(legs);
        return legs;
    }

    private MilestoneSettledCommand command(String actual, String seller) {
        String eventId = UUID.randomUUID().toString();
        return new MilestoneSettledCommand(eventId, eventId, CONTRACT, milestone.getMilestoneId(),
                new BigDecimal("300000000.00"), new BigDecimal(actual), seller, BUYER);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
