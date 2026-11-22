package com.agricontract.contract.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

/** D70: agricontract.files is file-service's; contract-service declares only its own queues (and their DLQs). */
class FilesExchangeNotDeclaredTest {

    @Test
    void theFileQueuesAreOurs_butTheExchangeAndItsBindingsAreNot() {
        var declarables = new RabbitMQConfig().contractDeclarables();

        assertThat(declarables.getDeclarablesByType(Exchange.class)).extracting(Exchange::getName)
                .doesNotContain(RabbitMQConfig.FILES_EXCHANGE);
        assertThat(declarables.getDeclarablesByType(Binding.class)).extracting(Binding::getExchange)
                .doesNotContain(RabbitMQConfig.FILES_EXCHANGE);
        assertThat(declarables.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .contains(RabbitMQConfig.FILE_READY_QUEUE, RabbitMQConfig.FILE_FAILED_QUEUE,
                        RabbitMQConfig.FILE_READY_QUEUE + ".dlq", RabbitMQConfig.FILE_FAILED_QUEUE + ".dlq");
    }
}
