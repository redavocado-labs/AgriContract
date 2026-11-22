package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.dto.RemedyFinalizedCommand;
import com.agricontract.escrow.application.exception.InvalidEventPayloadException;
import com.agricontract.escrow.application.usecase.DispatchRemedyLegsUseCase;
import com.agricontract.escrow.domain.model.RemedyLegInput;
import com.agricontract.escrow.infrastructure.config.RabbitMQConfig;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.InvalidEnvelopeException;
import com.agricontract.eventing.inbox.ProcessedEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * remedy.finalized (golden-flow-events.yaml remedyFinalized) → release, refund and seize commands (#249). The whole
 * payload is checked against the schema shape (every field, nothing else); a payload outside it is an
 * InvalidEventPayloadException: DLQ, no retry. The eventId is claimed and the decision, legs and bank commands written
 * in one transaction. A decision escrow rejects (mapping, scope, conservation) is acked, not dead-lettered; a bank
 * that cannot answer the ledger read throws, and the listener retries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemedyFinalizedConsumer {

    static final String CONSUMER_NAME = "escrow-service.remedy-finalized";
    static final String EVENT_TYPE = "remedy.finalized";
    static final String PRODUCER = "contract-service";

    private static final Set<String> FIELDS = Set.of("remedyDecisionId", "attributionDecisionId", "breachCaseId",
            "contractId", "buyerId", "sellerId", "affectedMilestoneIds", "settledMilestoneCount",
            "finalBreachingRole", "breachReasonCode", "qualityDisposition", "decisionSource", "penaltyEligible",
            "reputationEligible", "remedyLegs");
    private static final Set<String> LEG_FIELDS = Set.of("remedyLegId", "remedyType", "fundType", "role", "amount");
    private static final Set<String> REMEDY_TYPES = Set.of("NONE", "PAYMENT_SETTLEMENT", "PAYMENT_REFUND",
            "DEPOSIT_FORFEITURE", "CONTRACTUAL_PENALTY", "DAMAGES_COMPENSATION");
    private static final Set<String> FUND_TYPES = Set.of("BUYER_DEPOSIT", "SELLER_DEPOSIT", "MILESTONE_PAYMENT");
    private static final Set<String> ROLES = Set.of("BUYER", "SELLER");
    private static final Set<String> QUALITY = Set.of("CONFORMING", "PARTIALLY_CONFORMING", "NON_CONFORMING");
    private static final Set<String> DECISION_SOURCES = Set.of("SYSTEM", "ARBITRATOR", "INSPECTOR_L1_5",
            "INSPECTOR_L2", "MUTUAL");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    // $defs.money
    private static final Pattern MONEY = Pattern.compile("^[0-9]+\\.[0-9]{2}$");
    private static final int MAX_MONEY_LENGTH = 19;

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final DispatchRemedyLegsUseCase dispatchRemedyLegs;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.REMEDY_FINALIZED_QUEUE)
    public void onRemedyFinalized(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        RemedyFinalizedCommand command = toCommand(envelope);
        transactionTemplate.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(CONSUMER_NAME, envelope)) {
                log.info("event=remedy_finalized_duplicate eventId={} remedyDecisionId={}", envelope.eventId(),
                        command.remedyDecisionId());
                return;
            }
            dispatchRemedyLegs.execute(command);
        });
    }

    private EventEnvelope parse(byte[] body) {
        try {
            return codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope");
        }
    }

    RemedyFinalizedCommand toCommand(EventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version");
        }
        Map<String, Object> payload = envelope.payload();
        if (!payload.keySet().equals(FIELDS)) {
            throw new InvalidEventPayloadException("Payload fields do not match remedy.finalized");
        }
        String remedyDecisionId = uuid(payload.get("remedyDecisionId"), "remedyDecisionId");
        uuid(payload.get("attributionDecisionId"), "attributionDecisionId");
        if (payload.get("breachCaseId") != null) {
            uuid(payload.get("breachCaseId"), "breachCaseId");
        }
        String contractId = uuid(payload.get("contractId"), "contractId");
        String buyerId = uuid(payload.get("buyerId"), "buyerId");
        String sellerId = uuid(payload.get("sellerId"), "sellerId");
        List<String> milestones = new ArrayList<>();
        if (!(payload.get("affectedMilestoneIds") instanceof List<?> ids)) {
            throw new InvalidEventPayloadException("affectedMilestoneIds is not an array");
        }
        for (Object id : ids) {
            String milestoneId = uuid(id, "affectedMilestoneIds");
            if (milestones.contains(milestoneId)) {
                throw new InvalidEventPayloadException("affectedMilestoneIds is not unique");
            }
            milestones.add(milestoneId);
        }
        if (!(payload.get("settledMilestoneCount") instanceof Number count) || count.longValue() < 0 || count.doubleValue() != count.longValue()) {
            throw new InvalidEventPayloadException("settledMilestoneCount is not a non-negative integer");
        }
        nullableOneOf(payload.get("finalBreachingRole"), ROLES, "finalBreachingRole");
        if (payload.get("breachReasonCode") != null && !(payload.get("breachReasonCode") instanceof String)) {
            throw new InvalidEventPayloadException("breachReasonCode is not a string");
        }
        nullableOneOf(payload.get("qualityDisposition"), QUALITY, "qualityDisposition");
        if (!DECISION_SOURCES.contains(payload.get("decisionSource"))) {
            throw new InvalidEventPayloadException("decisionSource is not a decision source");
        }
        if (!(payload.get("penaltyEligible") instanceof Boolean) || !(payload.get("reputationEligible") instanceof Boolean)) {
            throw new InvalidEventPayloadException("penaltyEligible and reputationEligible must be booleans");
        }
        if (!(payload.get("remedyLegs") instanceof List<?> rawLegs)) {
            throw new InvalidEventPayloadException("remedyLegs is not an array");
        }
        List<RemedyLegInput> legs = new ArrayList<>();
        for (Object item : rawLegs) {
            if (!(item instanceof Map<?, ?> leg) || !leg.keySet().equals(LEG_FIELDS)) {
                throw new InvalidEventPayloadException("A remedy leg does not match its schema");
            }
            String legId = uuid(leg.get("remedyLegId"), "remedyLegs.remedyLegId");
            legs.add(new RemedyLegInput(legId,
                    oneOf(leg.get("remedyType"), REMEDY_TYPES, "remedyLegs.remedyType"),
                    oneOf(leg.get("fundType"), FUND_TYPES, "remedyLegs.fundType"),
                    oneOf(leg.get("role"), ROLES, "remedyLegs.role"),
                    money(leg.get("amount"))));
        }
        String eventId = envelope.eventId().toString();
        return new RemedyFinalizedCommand(eventId,
                envelope.correlationId() != null ? envelope.correlationId() : eventId,
                remedyDecisionId, contractId, buyerId, sellerId, List.copyOf(milestones), List.copyOf(legs));
    }

    private static String uuid(Object value, String field) {
        if (!(value instanceof String text) || !UUID.matcher(text).matches()) {
            throw new InvalidEventPayloadException(field + " is not a UUID");
        }
        return text.toLowerCase();
    }

    private static String oneOf(Object value, Set<String> allowed, String field) {
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw new InvalidEventPayloadException(field + " is not one of its allowed values");
        }
        return text;
    }

    private static void nullableOneOf(Object value, Set<String> allowed, String field) {
        if (value != null) {
            oneOf(value, allowed, field);
        }
    }

    private static BigDecimal money(Object value) {
        if (!(value instanceof String text) || text.length() > MAX_MONEY_LENGTH || !MONEY.matcher(text).matches()) {
            throw new InvalidEventPayloadException("remedyLegs.amount is not a money string");
        }
        return new BigDecimal(text);
    }
}
