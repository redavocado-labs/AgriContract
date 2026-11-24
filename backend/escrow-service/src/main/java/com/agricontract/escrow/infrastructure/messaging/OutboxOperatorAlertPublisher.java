package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.port.OperatorAlertPublisher;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * notification.escrow_remedy_leg_alert_requested and notification.escrow_remedy_rejected_requested
 * (notification-commands.yaml escrowRemedyLegAlertRequested, escrowRemedyRejectedRequested) in the commandEnvelope,
 * on escrow's own exchange agricontract.escrow (D70) with routing key = eventType, like every outbox row.
 */
@Component
@RequiredArgsConstructor
public class OutboxOperatorAlertPublisher implements OperatorAlertPublisher {

    static final String PRODUCER = "escrow-service";
    static final String REMEDY_LEG_ALERT = "notification.escrow_remedy_leg_alert_requested";
    static final String REMEDY_REJECTED = "notification.escrow_remedy_rejected_requested";
    static final int MAX_REASON = 500;

    private final OutboxWriter outbox;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remedyLegFailed(RemedyLeg leg, String party, Instant since, String correlationId,
                                String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", leg.getContractId());
        payload.put("remedyDecisionId", leg.getRemedyDecisionId());
        payload.put("remedyLegId", leg.getRemedyLegId());
        payload.put("party", party);
        payload.put("amount", leg.getAmount().amount().toPlainString());
        payload.put("bankCommandEventId", leg.getRequestEventId());
        payload.put("since", since.toString());
        append(REMEDY_LEG_ALERT, leg.getContractId(), correlationId, causationId, payload);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remedyRejected(String contractId, String remedyDecisionId, String reason, Instant rejectedAt,
                               String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("remedyDecisionId", remedyDecisionId);
        payload.put("reason", reason.substring(0, Math.min(reason.length(), MAX_REASON)));
        payload.put("rejectedAt", rejectedAt.toString());
        append(REMEDY_REJECTED, contractId, correlationId, causationId, payload);
    }

    private void append(String eventType, String contractId, String correlationId, String causationId,
                        Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        outbox.append(new EventEnvelope(eventId, eventType, 1, clock.instant(), PRODUCER, contractId,
                correlationId != null ? correlationId : eventId.toString(), causationId, null, payload));
    }
}
