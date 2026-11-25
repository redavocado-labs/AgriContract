package com.agricontract.inspection.infrastructure.messaging;

import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import com.agricontract.inspection.application.port.AssignmentAlerts;
import com.agricontract.inspection.domain.model.WaitingAssignment;
import com.agricontract.inspection.domain.model.TimedAssignment;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class OutboxAssignmentAlerts implements AssignmentAlerts {
    public static final String EVENT_TYPE = "notification.level1_5_assignment_waiting_requested";
    private static final Logger LOG = LoggerFactory.getLogger(OutboxAssignmentAlerts.class);
    private final OutboxWriter outbox;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assigned(TimedAssignment assignment, String email, String correlationId, String causationId) {
        var freeCancelUntil = java.util.Objects.requireNonNull(assignment.freeCancelUntil(), "Missing stored free cancellation deadline").toString();
        var common = Map.<String, Object>of("contractId", assignment.contractId(), "milestoneId", assignment.milestoneId(),
                "commissionId", assignment.commissionId(), "assignedAt", assignment.assignedAt().toString(),
                "freeCancelUntil", freeCancelUntil, "reportDueAt", assignment.reportDueAt().toString());
        outbox.append(new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_assigned", 1, clock.instant(),
                "inspection-service", assignment.milestoneId(), correlationId, causationId, null, common));
        var payload = Map.<String, Object>of("contractId", assignment.contractId(), "milestoneId", assignment.milestoneId(),
                "commissionId", assignment.commissionId(), "recipients", List.of(Map.of("userId", assignment.inspectorId(),
                        "email", email, "role", "INSPECTOR")), "reportDueAt", assignment.reportDueAt().toString(),
                "freeCancelUntil", freeCancelUntil, "stage", "ASSIGNED");
        outbox.append(new EventEnvelope(UUID.randomUUID(), "notification.level1_5_inspector_assignment_requested", 1,
                clock.instant(), "inspection-service", assignment.milestoneId(), correlationId, causationId, null, payload));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(TimedAssignment assignment, String email, String correlationId, String causationId) {
        var payload = Map.<String, Object>of("contractId", assignment.contractId(), "milestoneId", assignment.milestoneId(),
                "commissionId", assignment.commissionId(), "recipients", List.of(Map.of("userId", assignment.inspectorId(),
                        "email", email, "role", "INSPECTOR")), "reportDueAt", assignment.reportDueAt().toString(),
                "freeCancelUntil", java.util.Objects.requireNonNull(assignment.freeCancelUntil(), "Missing stored free cancellation deadline").toString(), "stage", "CANCELLED");
        outbox.append(new EventEnvelope(UUID.randomUUID(), "notification.level1_5_inspector_assignment_requested", 1,
                clock.instant(), "inspection-service", assignment.milestoneId(), correlationId, causationId, null, payload));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancellationNoop(String eventId, String contractId, String milestoneId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                LOG.info("inspection_level15_cancel_noop eventId={} contractId={} milestoneId={}",
                        eventId, contractId, milestoneId);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancellationTimeAdjusted(String eventId, TimedAssignment assignment, java.time.Instant cancelledAt) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                LOG.info("inspection_level15_cancel_time_adjusted eventId={} contractId={} milestoneId={} cancelledAt={} endedAt={}",
                        eventId, assignment.contractId(), assignment.milestoneId(), cancelledAt, assignment.assignedAt());
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void waitingChanged(WaitingAssignment waiting, Stage stage, String correlationId, String causationId) {
        UUID eventId = UUID.randomUUID();
        var payload = Map.<String, Object>of("contractId", waiting.contractId(), "milestoneId", waiting.milestoneId(),
                "reason", waiting.reason().name(), "waitingSince", waiting.waitingSince().toString(), "stage", stage.name());
        outbox.append(new EventEnvelope(eventId, EVENT_TYPE, 1, clock.instant(), "inspection-service",
                waiting.milestoneId(), correlationId, causationId, null, payload));
        // Preserve operational visibility after commit only; email delivery belongs to notification-service.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                LOG.warn("inspection_assignment_waiting audience=OPERATOR contractId={} milestoneId={} reason={} stage={}",
                        waiting.contractId(), waiting.milestoneId(), waiting.reason(), stage);
            }
        });
    }
}
