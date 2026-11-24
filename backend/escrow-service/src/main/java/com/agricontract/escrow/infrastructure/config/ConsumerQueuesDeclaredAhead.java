package com.agricontract.escrow.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue of a consumer not built yet, for an event escrow-service already writes to event_outbox.
 *
 * <p>The outbox poller publishes with mandatory=true and stops its whole batch on an event no queue receives (D23).
 * Without this queue the first {@code escrow.activation_refund_completed} would block every later escrow event.
 * Declared exactly as contract-service declares its escrow queues (queue {@code contract-svc.<routingKey>}, DLX
 * {@code agricontract.escrow.dlx}), so its own declaration (#234) is a no-op and the event kept here is consumed then.
 * {@code escrow.contract_lock_cleared} (#609, D93) is declared ahead the same way for contract-service.
 * Remove each once contract-service declares it.
 *
 * <p>The operator alerts of #609 (notification.escrow_remedy_leg_alert_requested,
 * notification.escrow_remedy_rejected_requested) likewise, for notification-service: queue
 * {@code notification-svc.<routingKey>}, DLX {@code notification-svc.escrow.dlx} (direct), the names and options its
 * topology.ts declares for notification.escrow_leg_alert_requested, so its own declaration is a no-op. Plain Queue/Binding beans, not a Declarables: DlqMonitor watches
 * escrow-service's own DLQs only.
 */
@Configuration
public class ConsumerQueuesDeclaredAhead {

    static final String REFUND_COMPLETED_KEY = "escrow.activation_refund_completed";
    static final String REFUND_COMPLETED_QUEUE = "contract-svc." + REFUND_COMPLETED_KEY;
    static final String CONTRACT_DLX = "agricontract.escrow.dlx";
    static final String LOCK_CLEARED_KEY = "escrow.contract_lock_cleared";
    static final String LOCK_CLEARED_QUEUE = "contract-svc." + LOCK_CLEARED_KEY;

    static final String NOTIFICATION_DLX = "notification-svc.escrow.dlx";
    static final String REMEDY_LEG_ALERT_KEY = "notification.escrow_remedy_leg_alert_requested";
    static final String REMEDY_REJECTED_KEY = "notification.escrow_remedy_rejected_requested";

    private static final TopicExchange ESCROW = new TopicExchange("agricontract.escrow", true, false);

    @Bean
    DirectExchange contractEscrowDlx() {
        return new DirectExchange(CONTRACT_DLX, true, false);
    }

    @Bean
    Queue contractRefundCompletedQueue() {
        return QueueBuilder.durable(REFUND_COMPLETED_QUEUE).deadLetterExchange(CONTRACT_DLX).build();
    }

    @Bean
    Queue contractRefundCompletedDlq() {
        return QueueBuilder.durable(REFUND_COMPLETED_QUEUE + ".dlq").build();
    }

    @Bean
    Binding contractRefundCompletedBinding() {
        return BindingBuilder.bind(contractRefundCompletedQueue()).to(ESCROW).with(REFUND_COMPLETED_KEY);
    }

    @Bean
    Binding contractRefundCompletedDlqBinding() {
        return BindingBuilder.bind(contractRefundCompletedDlq()).to(contractEscrowDlx()).with(REFUND_COMPLETED_KEY);
    }

    @Bean
    Queue contractLockClearedQueue() {
        return QueueBuilder.durable(LOCK_CLEARED_QUEUE).deadLetterExchange(CONTRACT_DLX).build();
    }

    @Bean
    Queue contractLockClearedDlq() {
        return QueueBuilder.durable(LOCK_CLEARED_QUEUE + ".dlq").build();
    }

    @Bean
    Binding contractLockClearedBinding() {
        return BindingBuilder.bind(contractLockClearedQueue()).to(ESCROW).with(LOCK_CLEARED_KEY);
    }

    @Bean
    Binding contractLockClearedDlqBinding() {
        return BindingBuilder.bind(contractLockClearedDlq()).to(contractEscrowDlx()).with(LOCK_CLEARED_KEY);
    }

    @Bean
    DirectExchange notificationEscrowDlx() {
        return new DirectExchange(NOTIFICATION_DLX, true, false);
    }

    @Bean
    Queue notificationRemedyLegAlertQueue() {
        return notificationQueue(REMEDY_LEG_ALERT_KEY);
    }

    @Bean
    Queue notificationRemedyLegAlertDlq() {
        return QueueBuilder.durable("notification-svc." + REMEDY_LEG_ALERT_KEY + ".dlq").build();
    }

    @Bean
    Binding notificationRemedyLegAlertBinding() {
        return BindingBuilder.bind(notificationRemedyLegAlertQueue()).to(ESCROW).with(REMEDY_LEG_ALERT_KEY);
    }

    @Bean
    Binding notificationRemedyLegAlertDlqBinding() {
        return BindingBuilder.bind(notificationRemedyLegAlertDlq()).to(notificationEscrowDlx())
                .with(REMEDY_LEG_ALERT_KEY);
    }

    @Bean
    Queue notificationRemedyRejectedQueue() {
        return notificationQueue(REMEDY_REJECTED_KEY);
    }

    @Bean
    Queue notificationRemedyRejectedDlq() {
        return QueueBuilder.durable("notification-svc." + REMEDY_REJECTED_KEY + ".dlq").build();
    }

    @Bean
    Binding notificationRemedyRejectedBinding() {
        return BindingBuilder.bind(notificationRemedyRejectedQueue()).to(ESCROW).with(REMEDY_REJECTED_KEY);
    }

    @Bean
    Binding notificationRemedyRejectedDlqBinding() {
        return BindingBuilder.bind(notificationRemedyRejectedDlq()).to(notificationEscrowDlx())
                .with(REMEDY_REJECTED_KEY);
    }

    private static Queue notificationQueue(String routingKey) {
        return QueueBuilder.durable("notification-svc." + routingKey).deadLetterExchange(NOTIFICATION_DLX).build();
    }
}
