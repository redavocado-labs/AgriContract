package com.agricontract.inspection.application.port;

import com.agricontract.inspection.domain.model.WaitingAssignment;
import com.agricontract.inspection.domain.model.TimedAssignment;

public interface AssignmentAlerts {
    enum Stage { WAITING_STARTED, STILL_WAITING, ASSIGNED, CANCELLED }
    void waitingChanged(WaitingAssignment waiting, Stage stage, String correlationId, String causationId);
    void assigned(TimedAssignment assignment, String email, String correlationId, String causationId);
    void cancelled(TimedAssignment assignment, String email, String correlationId, String causationId);
    void cancellationNoop(String eventId, String contractId, String milestoneId);
    void cancellationTimeAdjusted(String eventId, TimedAssignment assignment, java.time.Instant cancelledAt);
}
