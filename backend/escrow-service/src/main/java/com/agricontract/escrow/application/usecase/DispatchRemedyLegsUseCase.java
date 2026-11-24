package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.RemedyFinalizedCommand;
import com.agricontract.escrow.application.port.BankLedgerReader;
import com.agricontract.escrow.application.port.OperatorAlertPublisher;
import com.agricontract.escrow.application.port.RemedyCommandPublisher;
import com.agricontract.escrow.domain.exception.InvalidRemedyDecisionException;
import com.agricontract.escrow.domain.model.FundConservation;
import com.agricontract.escrow.domain.model.PlannedRemedyLeg;
import com.agricontract.escrow.domain.model.RemedyDecision;
import com.agricontract.escrow.domain.model.RemedyDecisionStatus;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.escrow.domain.model.RemedyLegPlanner;
import com.agricontract.escrow.domain.repository.DepositLegRepository;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import com.agricontract.escrow.domain.repository.OutstandingCommandRepository;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.Optional;

/**
 * remedy.finalized (#249): maps every leg to its bank command (RemedyLegPlanner), checks the leg set against the bank
 * ledger minus what escrow already has in flight (FundConservation), and only then writes the legs and their commands,
 * all in the caller's transaction. A decision that fails either step writes no leg and no command: it is stored
 * REJECTED (so a replay is ignored), logged at ERROR, notification.escrow_remedy_rejected_requested written once
 * (#609, the operator alert) and acked. A bank that cannot answer the
 * ledger read throws BankLedgerUnavailableException: retried, never dispatched without the check.
 * <p>
 * remedyDecisionId is the idempotency key: a second event for a decision escrow already holds sends nothing (INFO when
 * its legs are the same, ERROR when they differ).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchRemedyLegsUseCase {

    private final RemedyLegRepository remedyLegs;
    private final DepositLegRepository depositLegs;
    private final EscrowMilestoneRepository milestones;
    private final SettlementLegRepository settlementLegs;
    private final OutstandingCommandRepository outstanding;
    private final BankLedgerReader ledger;
    private final RemedyCommandPublisher bankCommands;
    private final OperatorAlertPublisher operatorAlerts;
    private final Clock clock;

    public void execute(RemedyFinalizedCommand command) {
        // #582: the contract-level lock comes first, before anything else is read FOR UPDATE. Looking up a decision
        // that does not exist yet takes a gap lock on remedy_decision; two dispatches holding that gap and then
        // inserting into it deadlocked. Under the contract lock they queue instead, and the second one sees the
        // first one's decision and pools.
        depositLegs.findByContractIdForUpdate(command.contractId());
        Optional<RemedyDecision> existing = remedyLegs.findDecisionForUpdate(command.remedyDecisionId());

        List<PlannedRemedyLeg> planned = null;
        String rejectReason = null;
        try {
            planned = RemedyLegPlanner.plan(command.buyerId(), command.sellerId(), command.affectedMilestoneIds(),
                    command.legs());
        } catch (InvalidRemedyDecisionException e) {
            rejectReason = e.getMessage();
        }

        if (existing.isPresent()) {
            logRepeated(existing.get(), command, planned);
            return;
        }

        if (planned != null && !planned.isEmpty()) {
            rejectReason = lockPools(command, planned);
        }
        if (planned != null && !planned.isEmpty() && rejectReason == null) {
            // The bank read comes first and may throw: nothing is written then, the event is retried.
            try {
                FundConservation.check(planned, ledger.entriesOf(command.contractId()),
                        outstanding.inFlight(command.contractId()));
            } catch (InvalidRemedyDecisionException e) {
                rejectReason = e.getMessage();
            }
        }

        Instant now = clock.instant();
        if (rejectReason != null) {
            RemedyDecision rejected = RemedyDecision.rejected(command.remedyDecisionId(), command.contractId(),
                    command.eventId(), rejectReason, now);
            remedyLegs.insertDecision(rejected);
            operatorAlerts.remedyRejected(command.contractId(), command.remedyDecisionId(), rejected.rejectReason(),
                    now, command.correlationId(), command.eventId());
            log.error("event=remedy_legs_rejected remedyDecisionId={} contractId={} reason={}: no command sent,"
                            + " OPERATOR must look at the decision", command.remedyDecisionId(), command.contractId(),
                    rejectReason);
            return;
        }

        remedyLegs.insertDecision(RemedyDecision.accepted(command.remedyDecisionId(), command.contractId(),
                command.eventId(), now));
        for (PlannedRemedyLeg plannedLeg : planned) {
            RemedyLeg leg = RemedyLeg.send(command.remedyDecisionId(), command.contractId(), plannedLeg, now);
            remedyLegs.insert(leg);
            bankCommands.requested(leg, command.correlationId(), command.eventId());
            log.info("event=remedy_leg_requested remedyDecisionId={} remedyLegId={} contractId={} entryType={}"
                            + " fundType={} amount={} sourceEventId={} correlationId={}", command.remedyDecisionId(),
                    leg.getRemedyLegId(), leg.getContractId(), leg.getEntryType(), leg.getFundType(),
                    leg.getAmount().amount().toPlainString(), leg.getRequestEventId(), command.correlationId());
        }
        if (planned.isEmpty()) {
            log.info("event=remedy_decision_without_legs remedyDecisionId={} contractId={}",
                    command.remedyDecisionId(), command.contractId());
        }
    }

    /**
     * The row locks every other money path of the contract takes, in their order (deposit legs, then milestones by
     * ascending id with their settlement legs), held until the transaction ends: two dispatches for one pool never
     * check the same available amount. Bank-service does not check balances, so this check is the only guard.
     * Returns a reject reason when a pool's milestone is unknown to escrow (nothing is locked for it), else null.
     */
    private String lockPools(RemedyFinalizedCommand command, List<PlannedRemedyLeg> planned) {
        // The deposit legs are already locked at the start of execute (#582).
        TreeSet<String> milestoneIds = new TreeSet<>();
        planned.stream().map(PlannedRemedyLeg::milestoneId).filter(Objects::nonNull).forEach(milestoneIds::add);
        for (String milestoneId : milestoneIds) {
            if (milestones.findByIdForUpdate(milestoneId).isEmpty()) {
                return "milestone " + milestoneId + " has no escrow funding: nothing is locked for it";
            }
            settlementLegs.findByMilestoneIdForUpdate(milestoneId);
        }
        return null;
    }

    private void logRepeated(RemedyDecision decision, RemedyFinalizedCommand command,
                             List<PlannedRemedyLeg> planned) {
        if (decision.status() == RemedyDecisionStatus.REJECTED) {
            log.info("event=remedy_decision_repeated remedyDecisionId={} firstEventId={} eventId={}: already REJECTED",
                    command.remedyDecisionId(), decision.eventId(), command.eventId());
            return;
        }
        List<RemedyLeg> stored = remedyLegs.findByDecisionId(command.remedyDecisionId());
        boolean same = planned != null && stored.size() == planned.size()
                && planned.stream().allMatch(p -> stored.stream().anyMatch(s -> s.sameAs(p)));
        if (same) {
            log.info("event=remedy_decision_repeated remedyDecisionId={} firstEventId={} eventId={}",
                    command.remedyDecisionId(), decision.eventId(), command.eventId());
        } else {
            log.error("event=remedy_decision_conflict remedyDecisionId={} firstEventId={} eventId={}: a repeated"
                            + " decision with other legs is never applied", command.remedyDecisionId(),
                    decision.eventId(), command.eventId());
        }
    }
}
