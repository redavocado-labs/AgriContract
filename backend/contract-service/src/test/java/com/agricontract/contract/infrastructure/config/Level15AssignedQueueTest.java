package com.agricontract.contract.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.aop.framework.ProxyFactory;

/**
 * #494: inspection.level1_5_assigned has its queue on inspection-service's exchange, dead-letters to the DLX with a
 * .dlq, and a malformed payload goes there at once (no retry). inspection.level1_5_cancelled reaches the
 * inspection-service request queue.
 */
class Level15AssignedQueueTest {

    @Test
    void theQueue_hasItsBinding_itsDlx_andItsDlq() {
        var declarables = new RabbitMQConfig().contractDeclarables();

        Queue queue = declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(RabbitMQConfig.INSPECTION_LEVEL1_5_ASSIGNED_QUEUE)).findFirst()
                .orElseThrow();
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "agricontract.escrow.dlx");
        assertThat(declarables.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .contains(RabbitMQConfig.INSPECTION_LEVEL1_5_ASSIGNED_QUEUE + ".dlq");
        assertThat(declarables.getDeclarablesByType(Binding.class)).anySatisfy(b -> {
            assertThat(b.getExchange()).isEqualTo("agricontract.inspection");
            assertThat(b.getDestination()).isEqualTo(RabbitMQConfig.INSPECTION_LEVEL1_5_ASSIGNED_QUEUE);
            assertThat(b.getRoutingKey()).isEqualTo("inspection.level1_5_assigned");
        });
        assertThat(declarables.getDeclarablesByType(Binding.class)).anySatisfy(b -> {
            assertThat(b.getExchange()).isEqualTo("agricontract.escrow.dlx");
            assertThat(b.getDestination()).isEqualTo(RabbitMQConfig.INSPECTION_LEVEL1_5_ASSIGNED_QUEUE + ".dlq");
        });
    }

    /** The shape of a listener method as the container calls it through the advice chain. */
    interface Listener {
        void onMessage(Object channel, Message message);
    }

    @Test
    void anInvalidPayload_isRejectedAfterOneAttempt() {
        AtomicInteger calls = new AtomicInteger();
        Listener target = (channel, message) -> {
            calls.incrementAndGet();
            throw new InvalidEventPayloadException("broken", null);
        };
        ProxyFactory factory = new ProxyFactory(target);
        factory.addInterface(Listener.class);
        factory.addAdvice(new RabbitMQConfig().retryInterceptor());
        Listener advised = (Listener) factory.getProxy();

        Throwable thrown = catchThrowable(() -> advised.onMessage(null,
                new Message(new byte[0], new MessageProperties())));

        assertThat(calls).as("attempts").hasValue(1);
        // RejectAndDontRequeueRecoverer: the container rejects without requeue, so the message dead-letters.
        assertThat(thrown).hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasRootCauseInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    void theCancelEvent_isRoutedToTheInspectionRequestQueue() {
        ConsumerQueuesDeclaredAhead ahead = new ConsumerQueuesDeclaredAhead();
        Binding binding = ahead.level15CancelledBinding();

        assertThat(binding.getExchange()).isEqualTo("agricontract.contracts");
        assertThat(binding.getDestination()).isEqualTo("inspection-svc.inspection.level1_5_requested");
        assertThat(binding.getRoutingKey()).isEqualTo("inspection.level1_5_cancelled");
        assertThat(ahead.inspectionRequestsQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", "agricontract.inspection.dlx");
    }
}
