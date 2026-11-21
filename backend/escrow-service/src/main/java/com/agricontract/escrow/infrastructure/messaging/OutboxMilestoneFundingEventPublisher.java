package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.port.MilestoneFundingEventPublisher;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * escrow.milestone_funded / escrow.milestone_funding_failed (golden-flow-events.yaml escrowMilestoneFunded,
 * escrowMilestoneFundingFailed) to agricontract.escrow, for contract-service to start the milestone or record the
 * failure (#119). The aggregate is the contract, like every escrow event.
 */
@Component
@RequiredArgsConstructor
public class OutboxMilestoneFundingEventPublisher implements MilestoneFundingEventPublisher {

    static final String PRODUCER = "escrow-service";
    static final String MILESTONE_FUNDED = "escrow.milestone_funded";
    static final String MILESTONE_FUNDING_FAILED = "escrow.milestone_funding_failed";
    /** The schema requires a non-empty reason; bank always sends one, this only guards a missing stored value. */
    static final String UNKNOWN_REASON = "unknown";

    private final OutboxWriter outbox;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneFunded(EscrowMilestone milestone, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", milestone.getContractId());
        payload.put("milestoneId", milestone.getMilestoneId());
        payload.put("lockedAt", milestone.getLockedAt().toString());
        append(MILESTONE_FUNDED, milestone, payload, correlationId, causationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneFundingFailed(EscrowMilestone milestone, String correlationId, String causationId) {
        String reason = milestone.getLastFailureReason();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", milestone.getContractId());
        payload.put("milestoneId", milestone.getMilestoneId());
        payload.put("reason", reason == null || reason.isEmpty() ? UNKNOWN_REASON : reason);
        append(MILESTONE_FUNDING_FAILED, milestone, payload, correlationId, causationId);
    }

    private void append(String eventType, EscrowMilestone milestone, Map<String, Object> payload,
                        String correlationId, String causationId) {
        outbox.append(new EventEnvelope(UUID.randomUUID(), eventType, 1, clock.instant(), PRODUCER,
                milestone.getContractId(), correlationId, causationId, null, payload));
    }
}
