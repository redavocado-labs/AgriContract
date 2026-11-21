package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.MilestoneFundingRequestedCommand;
import com.agricontract.escrow.application.port.MilestoneLockCommandPublisher;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * milestone.funding_requested → one funding per milestone → one bank.lock_requested LOCK_MILESTONE for exactly the
 * batch amount contract-service fixed (milestone-escrow design §6.2, §6b; D63). A redelivered event never reaches
 * here (processed_event). A second request with another eventId for a funded milestone keeps the first one (D69):
 * the same amount is a repeat, a different amount is a contract-service bug that is logged, never applied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestMilestoneFundingUseCaseImpl implements RequestMilestoneFundingUseCase {

    private final EscrowMilestoneRepository milestones;
    private final MilestoneLockCommandPublisher lockCommands;
    private final Clock clock;

    @Override
    public void execute(MilestoneFundingRequestedCommand command) {
        Money batchAmount = new Money(command.batchAmount());
        Optional<EscrowMilestone> existing = milestones.findByIdForUpdate(command.milestoneId());
        if (existing.isPresent()) {
            EscrowMilestone first = existing.get();
            if (first.isSameRequest(command.contractId(), command.buyerId(), batchAmount)) {
                log.info("event=milestone_funding_request_repeated milestoneId={} eventId={} firstEventId={}",
                        command.milestoneId(), command.eventId(), first.getFundingRequestEventId());
            } else {
                log.error("event=milestone_funding_request_conflict milestoneId={} eventId={} firstEventId={}"
                                + " contractId={} buyerId={} batchAmount={} keptContractId={} keptBuyerId={}"
                                + " keptBatchAmount={}",
                        command.milestoneId(), command.eventId(), first.getFundingRequestEventId(),
                        command.contractId(), command.buyerId(), batchAmount.amount().toPlainString(),
                        first.getContractId(), first.getBuyerId(), first.getBatchAmount().amount().toPlainString());
            }
            return;
        }

        EscrowMilestone milestone = EscrowMilestone.requestFunding(command.milestoneId(), command.contractId(),
                command.milestoneIndex(), command.buyerId(), batchAmount, command.requestedAt(), command.eventId(),
                clock.instant());
        milestones.insert(milestone);
        lockCommands.milestoneLockRequested(milestone, command.correlationId(), command.eventId());
        log.info("event=milestone_funding_requested contractId={} milestoneId={} index={} batchAmount={}"
                        + " sourceEventId={} correlationId={}",
                command.contractId(), command.milestoneId(), command.milestoneIndex(),
                batchAmount.amount().toPlainString(), milestone.getRequestEventId(), command.correlationId());
    }
}
