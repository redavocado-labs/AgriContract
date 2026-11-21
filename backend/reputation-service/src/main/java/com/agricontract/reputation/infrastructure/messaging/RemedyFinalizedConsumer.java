package com.agricontract.reputation.infrastructure.messaging;

import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.InvalidEnvelopeException;
import com.agricontract.reputation.application.dto.ProcessLockoutCommand;
import com.agricontract.reputation.application.exception.ImmutableFactConflictException;
import com.agricontract.reputation.application.port.TransactionRunner;
import com.agricontract.reputation.application.usecase.ProcessLockoutUseCase;
import com.agricontract.reputation.domain.RemedyAttribution.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RemedyFinalizedConsumer {
    private static final Set<String> FIELDS = Set.of("remedyDecisionId", "attributionDecisionId", "breachCaseId",
            "contractId", "buyerId", "sellerId", "affectedMilestoneIds", "settledMilestoneCount",
            "finalBreachingRole", "breachReasonCode", "qualityDisposition", "decisionSource", "penaltyEligible",
            "reputationEligible", "remedyLegs");
    private static final Set<String> LEG_FIELDS = Set.of("remedyLegId", "remedyType", "fundType", "role", "amount");
    private static final Set<String> REASONS = Set.of("NON_DELIVERY", "SHORTFALL", "LATE_DELIVERY",
            "QUALITY_BELOW_COMMITTED", "SIDE_SELLING", "PRODUCTION_SHOCK_NON_FM", "FUNDING_FAILURE",
            "LATE_PAYMENT", "FAILURE_TO_RECEIVE", "WRONGFUL_REJECTION", "FORCE_MAJEURE", "MUTUAL_EXIT", "ACTIVATION_FAILURE");
    private static final Set<String> TYPES = Set.of("NONE", "PAYMENT_SETTLEMENT", "PAYMENT_REFUND", "DEPOSIT_FORFEITURE", "CONTRACTUAL_PENALTY", "DAMAGES_COMPENSATION");
    private static final Set<String> FUNDS = Set.of("BUYER_DEPOSIT", "SELLER_DEPOSIT", "MILESTONE_PAYMENT");
    private static final Set<String> ROLES = Set.of("BUYER", "SELLER");
    private static final Set<String> DISPOSITIONS = Set.of("CONFORMING", "PARTIALLY_CONFORMING", "NON_CONFORMING");
    private static final Set<String> SOURCES = Set.of("SYSTEM", "ARBITRATOR", "INSPECTOR_L1_5", "INSPECTOR_L2", "MUTUAL");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern MONEY = Pattern.compile("^[0-9]+\\.[0-9]{2}$");
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    private final EventEnvelopeCodec codec;
    private final TransactionRunner transactions;
    private final RemedyDeliveryReceipt receipts;
    private final ProcessLockoutUseCase process;

    public RemedyFinalizedConsumer(EventEnvelopeCodec codec, TransactionRunner transactions,
                                   RemedyDeliveryReceipt receipts, ProcessLockoutUseCase process) {
        this.codec = codec;
        this.transactions = transactions;
        this.receipts = receipts;
        this.process = process;
    }

    @RabbitListener(queues = "reputation-svc.remedy.finalized")
    public void accept(Message message) {
        try {
            EventEnvelope envelope = parse(message.getBody());
            ProcessLockoutCommand command = command(envelope, milestoneCount(message.getBody()));
            String fingerprint = fingerprint(message.getBody());
            transactions.inTransaction(() -> {
                if (receipts.claim(envelope.eventId(), fingerprint)) {
                    process.execute(command);
                }
            });
        } catch (InvalidEventPayloadException | ImmutableFactConflictException e) {
            throw new AmqpRejectAndDontRequeueException("Invalid or conflicting remedy event");
        }
    }

    private EventEnvelope parse(byte[] body) {
        try {
            EventEnvelope envelope = codec.parse(body);
            var tree = CANONICAL.readTree(body);
            if (!tree.path("eventId").isTextual() || !UUID_PATTERN.matcher(tree.path("eventId").textValue()).matches()) {
                throw new InvalidEventPayloadException("Invalid eventId");
            }
            if (!"remedy.finalized".equals(envelope.eventType()) || !"contract-service".equals(envelope.producer())
                    || envelope.eventVersion() != 1) {
                throw new InvalidEventPayloadException("Unexpected event identity");
            }
            return envelope;
        } catch (InvalidEnvelopeException | java.io.IOException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Invalid event envelope");
        }
    }

    private static BigInteger milestoneCount(byte[] body) {
        try {
            var count = CANONICAL.readTree(body).path("payload").path("settledMilestoneCount");
            if (!count.isNumber()) throw invalid();
            var result = count.decimalValue().toBigIntegerExact();
            if (result.signum() < 0) throw invalid();
            return result;
        } catch (java.io.IOException | ArithmeticException e) {
            throw invalid();
        }
    }

    ProcessLockoutCommand command(EventEnvelope envelope, BigInteger count) {
        Map<String, Object> p = envelope.payload();
        if (!p.keySet().equals(FIELDS)) throw invalid();
        UUID decision = id(p.get("remedyDecisionId"));
        id(p.get("attributionDecisionId"));
        if (p.get("breachCaseId") != null) id(p.get("breachCaseId"));
        UUID contract = id(p.get("contractId"));
        UUID buyer = id(p.get("buyerId"));
        UUID seller = id(p.get("sellerId"));
        if (!(p.get("affectedMilestoneIds") instanceof List<?> milestones)) throw invalid();
        var seen = new HashSet<>();
        for (Object milestone : milestones) {
            id(milestone);
            if (!seen.add(milestone)) throw invalid();
        }
        String role = enumValue(p.get("finalBreachingRole"), ROLES, true);
        String reason = enumValue(p.get("breachReasonCode"), REASONS, true);
        String quality = enumValue(p.get("qualityDisposition"), DISPOSITIONS, true);
        enumValue(p.get("decisionSource"), SOURCES, false);
        if (!(p.get("penaltyEligible") instanceof Boolean penalty)
                || !(p.get("reputationEligible") instanceof Boolean eligible)) throw invalid();
        if ((role == null && (penalty || eligible)) || (eligible && reason == null)) throw invalid();
        if (("CONFORMING".equals(quality) || "PARTIALLY_CONFORMING".equals(quality))
                && (role != null || reason != null || penalty || eligible)) throw invalid();
        if ("NON_CONFORMING".equals(quality)
                && (!"SELLER".equals(role) || !"QUALITY_BELOW_COMMITTED".equals(reason) || !penalty || !eligible)) throw invalid();
        if (!(p.get("remedyLegs") instanceof List<?> legs)) throw invalid();
        for (Object item : legs) {
            if (!(item instanceof Map<?, ?> leg) || !leg.keySet().equals(LEG_FIELDS)) throw invalid();
            id(leg.get("remedyLegId"));
            String type = enumValue(leg.get("remedyType"), TYPES, false);
            enumValue(leg.get("fundType"), FUNDS, false);
            enumValue(leg.get("role"), ROLES, false);
            if (!(leg.get("amount") instanceof String amount) || amount.length() > 19 || !MONEY.matcher(amount).matches()) throw invalid();
            if ((!penalty && "CONTRACTUAL_PENALTY".equals(type))
                    || (role == null && !Set.of("NONE", "PAYMENT_SETTLEMENT", "PAYMENT_REFUND").contains(type))) throw invalid();
        }
        return new ProcessLockoutCommand(envelope.eventId(), decision, contract, buyer, seller,
                role == null ? null : Role.valueOf(role), reason, eligible, count,
                envelope.occurredAt(), envelope.correlationId());
    }

    private static UUID id(Object value) {
        if (!(value instanceof String text) || !UUID_PATTERN.matcher(text).matches()) throw invalid();
        return UUID.fromString(text);
    }

    private static String enumValue(Object value, Set<String> allowed, boolean nullable) {
        if (value == null && nullable) return null;
        if (!(value instanceof String text) || !allowed.contains(text)) throw invalid();
        return text;
    }

    private static InvalidEventPayloadException invalid() {
        return new InvalidEventPayloadException("Invalid remedy payload");
    }

    private static String fingerprint(byte[] body) {
        try {
            Object value = CANONICAL.readValue(body, Object.class);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(value)));
        } catch (java.io.IOException e) {
            throw new InvalidEventPayloadException("Invalid event fingerprint input");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
