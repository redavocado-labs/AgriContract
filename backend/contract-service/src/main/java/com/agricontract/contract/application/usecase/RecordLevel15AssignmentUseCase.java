package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.Level15AssignedCommand;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.inspection.Level15Assignment;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.repository.Level15AssignmentRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * inspection.level1_5_assigned (D57, D92): keeps the assignment so a dispute exit can tell whether the inspection is
 * still free to cancel (freeCancelUntil). Runs in the consumer's transaction (the eventId was claimed there). An
 * assignment for an unknown milestone, one of another contract, or a milestone whose dispute already ended (SETTLED)
 * is acknowledged and ignored with a log line. A commission already stored is a no-op.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordLevel15AssignmentUseCase {
    private final MilestoneRepository milestones;
    private final Level15AssignmentRepository assignments;
    private final TransactionRunner transactions;

    public void execute(Level15AssignedCommand command) {
        transactions.requireTransaction();
        Milestone milestone = milestones.findById(command.milestoneId()).orElse(null);
        if (milestone == null || !milestone.contractId().equals(command.contractId())) {
            ignore(command, milestone == null ? "unknown_milestone" : "contract_mismatch");
            return;
        }
        if (milestone.status() == MilestoneStatus.SETTLED) {
            ignore(command, "dispute_ended");
            return;
        }
        boolean stored = assignments.insertIfAbsent(new Level15Assignment(command.commissionId(),
                command.milestoneId(), command.assignedAt(), command.freeCancelUntil(), command.reportDueAt()));
        log.info("event=level1_5_assignment_recorded milestoneId={} commissionId={} freeCancelUntil={} new={}",
                command.milestoneId(), command.commissionId(), command.freeCancelUntil(), stored);
    }

    private static void ignore(Level15AssignedCommand command, String reason) {
        log.info("event=level1_5_assignment_ignored reason={} contractId={} milestoneId={} commissionId={} eventId={}",
                reason, command.contractId(), command.milestoneId(), command.commissionId(), command.eventId());
    }
}
