package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.port.SettlementCommandPublisher;
import com.agricontract.escrow.domain.model.SettlementLeg;
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
 * bank.release_requested / bank.refund_requested of a settlement leg (golden-flow-events.yaml bankReleaseRequested /
 * bankRefundRequested). eventId = payload.sourceEventId = the leg's requestEventId: bank-service dedups on it, so a
 * resend reuses the original envelope (D27).
 */
@Component
@RequiredArgsConstructor
public class OutboxSettlementCommandPublisher implements SettlementCommandPublisher {

    static final String PRODUCER = "escrow-service";

    private final OutboxWriter outbox;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    @Override
    public void settlementLegRequested(SettlementLeg leg, String correlationId, String causationId) {
        // Nullable fields stay on the wire as null: the schema lists them as required.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", leg.getContractId());
        payload.put("milestoneId", leg.getMilestoneId());
        payload.put("userId", leg.getUserId());
        payload.put("entryType", leg.getType().name());
        payload.put("fundType", leg.getFundType());
        payload.put("remedyDecisionId", null);      // clean path: no remedy decision
        payload.put("remedyLegId", null);
        payload.put("amount", leg.getAmount().amount().toPlainString());
        payload.put("sourceEventId", leg.getRequestEventId());

        // The bank pool is per contract (bank design §2b.3), so the contract is the aggregate.
        outbox.append(new EventEnvelope(UUID.fromString(leg.getRequestEventId()), leg.getType().command(), 1,
                clock.instant(), PRODUCER, leg.getContractId(), correlationId, causationId, null, payload));
    }

    /**
     * The original envelope's event_outbox row goes back to PENDING (event_id is UNIQUE there, and a new envelope
     * would need a new id, which bank would treat as a new command). created_at is reset so the stale monitor
     * measures from the resend.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void resend(SettlementLeg leg) {
        int reset = jdbc.update("UPDATE event_outbox SET status = 'PENDING', published_at = NULL, attempts = 0,"
                + " last_error = NULL, created_at = NOW(3) WHERE event_id = ? AND event_type = ?",
                leg.getRequestEventId(), leg.getType().command());
        if (reset != 1) {
            throw new IllegalStateException("No " + leg.getType().command() + " in event_outbox for settlement leg "
                    + leg.getLegId() + " (" + leg.getRequestEventId() + ")");
        }
    }
}
