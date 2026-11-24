package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.port.ContractClearanceEventPublisher;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * escrow.contract_lock_cleared (golden-flow-events.yaml escrowContractLockCleared) to agricontract.escrow, for
 * contract-service to commit SETTLED / TERMINATED (D93). It starts its own chain: correlationId = eventId, no
 * causation (it answers the ledger, not one event). Publishing again for the same contract is allowed:
 * contract-service ignores a cleared lock without its pending decision.
 */
@Component
@RequiredArgsConstructor
public class OutboxContractClearanceEventPublisher implements ContractClearanceEventPublisher {

    static final String PRODUCER = "escrow-service";
    static final String CONTRACT_LOCK_CLEARED = "escrow.contract_lock_cleared";

    private final OutboxWriter outbox;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractLockCleared(String contractId, List<String> remedyDecisionIds, Instant clearedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("remedyDecisionIds", List.copyOf(remedyDecisionIds));
        payload.put("clearedAt", clearedAt.toString());

        UUID eventId = UUID.randomUUID();
        outbox.append(new EventEnvelope(eventId, CONTRACT_LOCK_CLEARED, 1, clock.instant(), PRODUCER, contractId,
                eventId.toString(), null, null, payload));
    }
}
