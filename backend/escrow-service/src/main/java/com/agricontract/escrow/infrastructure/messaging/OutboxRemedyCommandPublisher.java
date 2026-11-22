package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.port.RemedyCommandPublisher;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * bank.release_requested / bank.refund_requested / bank.seize_requested of a remedy leg (golden-flow-events.yaml
 * bankReleaseRequested, bankRefundRequested, bankSeizeRequested). eventId = payload.sourceEventId = the leg's
 * requestEventId: bank-service dedups on it, so a resend reuses the original envelope (D27).
 */
@Component
@RequiredArgsConstructor
public class OutboxRemedyCommandPublisher implements RemedyCommandPublisher {

    static final String PRODUCER = "escrow-service";

    private final OutboxWriter outbox;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    @Override
    public void requested(RemedyLeg leg, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", leg.getContractId());
        payload.put("milestoneId", leg.getMilestoneId());
        payload.put("userId", leg.getUserId());
        payload.put("entryType", leg.getEntryType().name());
        payload.put("fundType", leg.getFundType());
        payload.put("remedyDecisionId", leg.getRemedyDecisionId());
        payload.put("remedyLegId", leg.getRemedyLegId());
        payload.put("amount", leg.getAmount().amount().toPlainString());
        payload.put("sourceEventId", leg.getRequestEventId());

        outbox.append(new EventEnvelope(UUID.fromString(leg.getRequestEventId()), leg.getEntryType().command(), 1,
                clock.instant(), PRODUCER, leg.getContractId(), correlationId, causationId, null, payload));
    }

    /** The original envelope's event_outbox row goes back to PENDING, as OutboxSettlementCommandPublisher does. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void resend(RemedyLeg leg) {
        int reset = jdbc.update("UPDATE event_outbox SET status = 'PENDING', published_at = NULL, attempts = 0,"
                + " last_error = NULL, created_at = NOW(3) WHERE event_id = ? AND event_type = ?",
                leg.getRequestEventId(), leg.getEntryType().command());
        if (reset != 1) {
            throw new IllegalStateException("No " + leg.getEntryType().command() + " in event_outbox for remedy leg "
                    + leg.getRemedyLegId() + " (" + leg.getRequestEventId() + ")");
        }
    }
}
