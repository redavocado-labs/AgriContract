package com.agricontract.contract.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

/** #246: the report_confirmed queue is bound to inspection-service's exchange, dead-letters to the DLX, has a .dlq. */
class InspectionReportQueueTest {

    @Test
    void theQueue_hasItsBinding_itsDlx_andItsDlq() {
        var declarables = new RabbitMQConfig().contractDeclarables();

        Queue queue = declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(RabbitMQConfig.INSPECTION_REPORT_CONFIRMED_QUEUE)).findFirst()
                .orElseThrow();
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "agricontract.escrow.dlx");
        assertThat(declarables.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .contains(RabbitMQConfig.INSPECTION_REPORT_CONFIRMED_QUEUE + ".dlq");
        assertThat(declarables.getDeclarablesByType(Binding.class)).anySatisfy(b -> {
            assertThat(b.getExchange()).isEqualTo("agricontract.inspection");
            assertThat(b.getDestination()).isEqualTo(RabbitMQConfig.INSPECTION_REPORT_CONFIRMED_QUEUE);
            assertThat(b.getRoutingKey()).isEqualTo("inspection.report_confirmed");
        });
    }
}
