package com.agricontract.escrow.application.port;

import com.agricontract.escrow.domain.model.RemedyLeg;

import java.time.Instant;

/**
 * Staff-only notification commands of escrow (#609, D93; notification-commands.yaml). No recipients: notification-
 * service mails notification.operatorEmail. Must run inside the caller's transaction.
 */
public interface OperatorAlertPublisher {

    /**
     * notification.escrow_remedy_leg_alert_requested: the leg was refused with no resend left. party is the payee
     * role, since the time of the last refusal.
     */
    void remedyLegFailed(RemedyLeg leg, String party, Instant since, String correlationId, String causationId);

    /** notification.escrow_remedy_rejected_requested: the whole decision was refused, no bank command sent. */
    void remedyRejected(String contractId, String remedyDecisionId, String reason, Instant rejectedAt,
                        String correlationId, String causationId);
}
