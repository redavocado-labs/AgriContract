package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.MilestoneSettledCommand;
import com.agricontract.escrow.application.port.SettlementCommandPublisher;
import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.SettlementLeg;
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
 * sends nothing: the same amounts are a repeat (logged), different ones a contract-service bug (error log, never
 * applied). A settlement that breaks the lock (unknown or unlocked milestone, wrong contract, buyer or lock amount,
 * actualAmount above the lock, no seller to pay) is refused: DLQ, no retry, nothing sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettleMilestoneUseCase {

    private final EscrowMilestoneRepository milestones;
    private final SettlementLegRepository settlementLegs;
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
            } else {
                log.error("event=milestone_settlement_conflict milestoneId={} eventId={} firstEventId={}"
                                + " lockedAmount={} actualAmount={}: a second settlement with other amounts is never"
                                + " applied", command.milestoneId(), command.eventId(),
                        milestone.getSettlementEventId(), command.lockedAmount().toPlainString(),
                        command.actualAmount().toPlainString());
            }
            return;
        }

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
}
