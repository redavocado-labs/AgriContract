package com.agricontract.contract.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.exception.TerminalCommitRefusedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.aop.framework.ProxyFactory;

/**
 * #139, D93: escrow.contract_lock_cleared has its queue on escrow's exchange, dead-letters to contract-service's DLX
 * with a .dlq, and a malformed payload or an unknown contract goes there at once (no retry).
 */
class ContractLockClearedQueueTest {

    @Test
    void theQueue_hasItsBinding_itsDlx_andItsDlq() {
        var declarables = new RabbitMQConfig().contractDeclarables();

        Queue queue = declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(RabbitMQConfig.CONTRACT_LOCK_CLEARED_QUEUE)).findFirst()
                .orElseThrow();
        assertThat(queue.getName()).isEqualTo("contract-svc.escrow.contract_lock_cleared");
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "agricontract.escrow.dlx");
        assertThat(declarables.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .contains(RabbitMQConfig.CONTRACT_LOCK_CLEARED_QUEUE + ".dlq");
        assertThat(declarables.getDeclarablesByType(Binding.class)).anySatisfy(b -> {
            assertThat(b.getExchange()).isEqualTo("agricontract.escrow");
            assertThat(b.getDestination()).isEqualTo(RabbitMQConfig.CONTRACT_LOCK_CLEARED_QUEUE);
            assertThat(b.getRoutingKey()).isEqualTo("escrow.contract_lock_cleared");
        });
        assertThat(declarables.getDeclarablesByType(Binding.class)).anySatisfy(b -> {
            assertThat(b.getExchange()).isEqualTo("agricontract.escrow.dlx");
            assertThat(b.getDestination()).isEqualTo(RabbitMQConfig.CONTRACT_LOCK_CLEARED_QUEUE + ".dlq");
            assertThat(b.getRoutingKey()).isEqualTo("escrow.contract_lock_cleared");
        });
    }

    /** The shape of a listener method as the container calls it through the advice chain. */
    interface Listener {
        void onMessage(Object channel, Message message);
    }

    @Test
    void aMalformedPayloadOrAnUnknownContract_isRejectedAfterOneAttempt() {
        for (RuntimeException failure : List.of(new InvalidEventPayloadException("broken", null),
                new TerminalCommitRefusedException("unknown contract"))) {
            AtomicInteger calls = new AtomicInteger();
            Listener target = (channel, message) -> {
                calls.incrementAndGet();
                throw failure;
            };
            ProxyFactory factory = new ProxyFactory(target);
            factory.addInterface(Listener.class);
            factory.addAdvice(new RabbitMQConfig().retryInterceptor());
            Listener advised = (Listener) factory.getProxy();

            Throwable thrown = catchThrowable(() -> advised.onMessage(null,
                    new Message(new byte[0], new MessageProperties())));

            assertThat(calls).as("attempts for " + failure.getClass().getSimpleName()).hasValue(1);
            // RejectAndDontRequeueRecoverer: the container rejects without requeue, so the message dead-letters.
            assertThat(thrown).hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasRootCauseInstanceOf(failure.getClass());
        }
    }
}
