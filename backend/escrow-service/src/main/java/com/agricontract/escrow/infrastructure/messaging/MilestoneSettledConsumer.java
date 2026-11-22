package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.dto.MilestoneSettledCommand;
import com.agricontract.escrow.application.exception.InvalidEventPayloadException;
import com.agricontract.escrow.application.usecase.SettleMilestoneUseCase;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * milestone.settled (golden-flow-events.yaml milestoneSettled) → release and refund commands (#248). A payload outside
 * the schema is an InvalidEventPayloadException: DLQ, no retry. The eventId is claimed and the settlement legs + bank
 * commands written in one transaction. recipients' emails are read for shape only, never logged or kept.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneSettledConsumer {

    static final String CONSUMER_NAME = "escrow-service.milestone-settled";
    static final String EVENT_TYPE = "milestone.settled";
    static final String PRODUCER = "contract-service";

    // Every field is required and nothing else is allowed (additionalProperties: false).
    private static final Set<String> FIELDS = Set.of("contractId", "milestoneId", "lockedAmount", "actualAmount",
            "recipients");
    private static final Set<String> RECIPIENT_FIELDS = Set.of("userId", "email", "role");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    // $defs.money rounded to the whole dong (D65): contract-service rounds every amount once, escrow never re-rounds.
    private static final Pattern POSITIVE_WHOLE_DONG = Pattern.compile("^[1-9][0-9]*\\.00$");
    private static final Pattern WHOLE_DONG = Pattern.compile("^(?:0|[1-9][0-9]*)\\.00$");
    private static final int MAX_MONEY_LENGTH = 19;

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final SettleMilestoneUseCase settleMilestone;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.MILESTONE_SETTLED_QUEUE)
    public void onMilestoneSettled(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        MilestoneSettledCommand command = toCommand(envelope);
        transactionTemplate.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(CONSUMER_NAME, envelope)) {
                log.info("event=milestone_settled_duplicate eventId={} milestoneId={}", envelope.eventId(),
                        command.milestoneId());
                return;
            }
            settleMilestone.execute(command);
        });
    }

    private EventEnvelope parse(byte[] body) {
        try {
            return codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope");
        }
    }

    MilestoneSettledCommand toCommand(EventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version");
        }
        Map<String, Object> payload = envelope.payload();
        if (!payload.keySet().equals(FIELDS)) {
            throw new InvalidEventPayloadException("Payload fields do not match milestone.settled");
        }
        String sellerId = null;
        String buyerId = null;
        if (!(payload.get("recipients") instanceof List<?> recipients)) {
            throw new InvalidEventPayloadException("recipients is not an array");
        }
        for (Object item : recipients) {
            if (!(item instanceof Map<?, ?> recipient) || !recipient.keySet().equals(RECIPIENT_FIELDS)) {
                throw new InvalidEventPayloadException("A recipient does not match {userId, email, role}");
            }
            String userId = uuid(recipient.get("userId"), "recipients.userId");
            if (!(recipient.get("email") instanceof String email) || email.indexOf('@') < 1) {
                throw new InvalidEventPayloadException("recipients.email is not an email");
            }
            Object role = recipient.get("role");
            if ("SELLER".equals(role) && sellerId == null) {
                sellerId = userId;
            } else if ("BUYER".equals(role) && buyerId == null) {
                buyerId = userId;
            } else {
                // A second recipient for one role leaves the payee ambiguous: never guess who is paid.
                throw new InvalidEventPayloadException("recipients.role is not BUYER or SELLER, or is repeated");
            }
        }
        String eventId = envelope.eventId().toString();
        return new MilestoneSettledCommand(
                eventId,
                envelope.correlationId() != null ? envelope.correlationId() : eventId,
                uuid(payload.get("contractId"), "contractId"),
                uuid(payload.get("milestoneId"), "milestoneId"),
                money(payload.get("lockedAmount"), "lockedAmount", POSITIVE_WHOLE_DONG),
                money(payload.get("actualAmount"), "actualAmount", WHOLE_DONG),
                sellerId,
                buyerId);
    }

    private static String uuid(Object value, String field) {
        if (!(value instanceof String text) || !UUID.matcher(text).matches()) {
            throw new InvalidEventPayloadException(field + " is not a UUID");
        }
        return text.toLowerCase();
    }

    private static BigDecimal money(Object value, String field, Pattern pattern) {
        if (!(value instanceof String text) || text.length() > MAX_MONEY_LENGTH || !pattern.matcher(text).matches()) {
            throw new InvalidEventPayloadException(field + " is not a whole-dong money string");
        }
        return new BigDecimal(text);
    }
}
