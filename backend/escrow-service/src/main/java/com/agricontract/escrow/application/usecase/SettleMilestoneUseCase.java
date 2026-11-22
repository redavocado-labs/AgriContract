package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.MilestoneSettledCommand;
import com.agricontract.escrow.application.port.SettlementCommandPublisher;
import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import com.agricontract.escrow.domain.model.DepositLegParty;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.SettlementLeg;
import com.agricontract.escrow.domain.repository.DepositLegRepository;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * milestone.settled, the clean path (#248; milestone-escrow design §7.1, D65, D77/D78): under the milestone's row
 * lock, checks lockedAmount is the batch escrow locked and lockedAmount >= actualAmount, then writes
 * bank.release_requested RELEASE_TO_SELLER(actualAmount) and bank.refund_requested REFUND_TO_BUYER(lockedAmount -
 * actualAmount), each only when positive. Escrow never recomputes the amount: contract-service computed and rounded it.
 * <p>
 * A redelivered event never reaches here (processed_event). Another milestone.settled for a milestone already settling
 * sends nothing: the same amounts and seller are a repeat (logged, acked); different ones are a contract-service bug,
 * refused to the DLQ so OPERATOR sees it (D40/D68). A settlement that breaks the lock (unknown or unlocked milestone,
 * wrong contract, buyer or lock amount, actualAmount above the lock, no seller to pay, a seller other than the one
 * whose deposit escrow holds) is refused: DLQ, no retry, nothing sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettleMilestoneUseCase {

    private final EscrowMilestoneRepository milestones;
    private final SettlementLegRepository settlementLegs;
    private final DepositLegRepository depositLegs;
    private final SettlementCommandPublisher bankCommands;
    private final Clock clock;

    public void execute(MilestoneSettledCommand command) {
        EscrowMilestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                .orElseThrow(() -> new InvalidDepositLegException("milestone.settled " + command.eventId()
                        + " for milestone " + command.milestoneId() + ", which escrow never funded"));
        if (command.buyerId() != null && !milestone.getBuyerId().equals(command.buyerId())) {
            throw new InvalidDepositLegException("milestone.settled " + command.eventId() + " names buyer "
                    + command.buyerId() + ", the batch of milestone " + command.milestoneId() + " is locked from "
                    + milestone.getBuyerId());
        }
        Money lockedAmount = new Money(command.lockedAmount());

        if (milestone.isSettlementStarted()) {
            List<SettlementLeg> legs = settlementLegs.findByMilestoneIdForUpdate(command.milestoneId());
            if (milestone.isSameSettlement(legs, command.contractId(), lockedAmount, command.actualAmount(),
                    command.sellerId())) {
                log.info("event=milestone_settlement_repeated milestoneId={} eventId={} firstEventId={}",
                        command.milestoneId(), command.eventId(), milestone.getSettlementEventId());
                return;
            }
            log.error("event=milestone_settlement_conflict milestoneId={} eventId={} firstEventId={}"
                            + " lockedAmount={} actualAmount={}: a second settlement with other amounts or seller is"
                            + " never applied", command.milestoneId(), command.eventId(),
                    milestone.getSettlementEventId(), command.lockedAmount().toPlainString(),
                    command.actualAmount().toPlainString());
            // To the DLQ, not acked: a conflicting settlement must stay visible for OPERATOR (D40/D68).
            throw new InvalidDepositLegException("milestone.settled " + command.eventId() + " conflicts with the"
                    + " settlement " + milestone.getSettlementEventId() + " of milestone " + command.milestoneId());
        }
        requireDepositSeller(command);

        List<SettlementLeg> legs = milestone.settle(command.contractId(), lockedAmount, command.actualAmount(),
                command.sellerId(), command.eventId(), clock.instant());
        milestones.update(milestone);
        for (SettlementLeg leg : legs) {
            settlementLegs.insert(leg);
            bankCommands.settlementLegRequested(leg, command.correlationId(), command.eventId());
            log.info("event=milestone_settlement_leg_requested contractId={} milestoneId={} entryType={} amount={}"
                            + " sourceEventId={} correlationId={}", command.contractId(), command.milestoneId(),
                    leg.getType(), leg.getAmount().amount().toPlainString(), leg.getRequestEventId(),
                    command.correlationId());
        }
    }

    /**
     * When the contract has a seller deposit, escrow already knows the seller from contract.signed: the payee named
     * in recipients must be that same user, or the sale proceeds could go to someone else.
     */
    private void requireDepositSeller(MilestoneSettledCommand command) {
        if (command.sellerId() == null) {
            return;
        }
        depositLegs.findByContractId(command.contractId()).stream()
                .filter(leg -> leg.getParty() == DepositLegParty.SELLER)
                .findFirst()
                .filter(leg -> !leg.getUserId().equals(command.sellerId()))
                .ifPresent(leg -> {
                    throw new InvalidDepositLegException("milestone.settled " + command.eventId() + " pays seller "
                            + command.sellerId() + ", the seller deposit of contract " + command.contractId()
                            + " belongs to " + leg.getUserId());
                });
    }
}
