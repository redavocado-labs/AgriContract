package com.agricontract.audit.infrastructure.config;

import com.agricontract.audit.domain.exception.InvalidAuditRecordException;
import com.agricontract.audit.infrastructure.messaging.CatalogEvent;
import com.agricontract.audit.infrastructure.messaging.DlqMonitor;
import com.agricontract.audit.infrastructure.messaging.InvalidEventPayloadException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Source events arrive on the producer's exchange. audit-service has its own DLX, as every new consumer must:
 * agricontract.audit.dlx is used by this service only.
 */
@Configuration
@EnableScheduling
public class RabbitConfiguration {

    public static final String CONTRACTS_EXCHANGE = "agricontract.contracts";
    public static final String DLX = "agricontract.audit.dlx";
    public static final String CONTRACT_SIGNED_KEY = "contract.signed";
    public static final String CONTRACT_SIGNED_QUEUE = "audit-svc." + CONTRACT_SIGNED_KEY;
    public static final String CONTRACT_SIGNED_DLQ = CONTRACT_SIGNED_QUEUE + ".dlq";
    /** contract.signed and every #255 catalog queue's DLQ, for the DlqMonitor. */
    public static final List<String> DLQS = Stream.concat(Stream.of(CONTRACT_SIGNED_DLQ),
            CatalogEvent.ALL.stream().map(e -> CatalogEvent.queue(e.eventType()) + ".dlq")).toList();

    /** The #255 catalog queues CatalogEventConsumer listens on (audit-svc.<eventType>). */
    public static String[] catalogQueues() {
        return CatalogEvent.ALL.stream().map(e -> CatalogEvent.queue(e.eventType())).toArray(String[]::new);
    }

    @Bean
    Declarables auditTopology() {
        TopicExchange contracts = new TopicExchange(CONTRACTS_EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DLX, true, false);
        List<Declarable> declarables = new ArrayList<>(List.of(contracts, dlx));
        Queue queue = QueueBuilder.durable(CONTRACT_SIGNED_QUEUE).deadLetterExchange(DLX).build();
        Queue dlq = QueueBuilder.durable(CONTRACT_SIGNED_DLQ).build();
        declarables.add(queue);
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(queue).to(contracts).with(CONTRACT_SIGNED_KEY));
        // Dead-lettered messages keep their original routing key.
        declarables.add(BindingBuilder.bind(dlq).to(dlx).with(CONTRACT_SIGNED_KEY));
        // #255: one queue per catalog event on its producer's topic exchange, the same names contract-service
        // declared ahead (audit-svc.<eventType>, this DLX), so its declarations are no-ops.
        Map<String, TopicExchange> exchanges = new LinkedHashMap<>();
        exchanges.put(CONTRACTS_EXCHANGE, contracts);
        for (CatalogEvent event : CatalogEvent.ALL) {
            TopicExchange exchange = exchanges.computeIfAbsent(event.exchange(),
                    name -> {
                        TopicExchange created = new TopicExchange(name, true, false);
                        declarables.add(created);
                        return created;
                    });
            String name = CatalogEvent.queue(event.eventType());
            Queue catalogQueue = QueueBuilder.durable(name).deadLetterExchange(DLX).build();
            Queue catalogDlq = QueueBuilder.durable(name + ".dlq").build();
            declarables.add(catalogQueue);
            declarables.add(catalogDlq);
            declarables.add(BindingBuilder.bind(catalogQueue).to(exchange).with(event.eventType()));
            declarables.add(BindingBuilder.bind(catalogDlq).to(dlx).with(event.eventType()));
        }
        return new Declarables(declarables);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        // A broken payload fails the same way every time: straight to the DLQ. Other failures (database down) get
        // three attempts first.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3,
                Map.of(InvalidEventPayloadException.class, false, InvalidAuditRecordException.class, false,
                        AmqpRejectAndDontRequeueException.class, false),
                true, true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffOptions(1000, 2, 4000)
                .recoverer((message, cause) -> {
                    // The default recoverer logs the whole message, payload included.
                    throw new AmqpRejectAndDontRequeueException("audit event rejected to the DLQ", cause);
                })
                .build());
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "audit.dlq-monitor.enabled", havingValue = "true", matchIfMissing = true)
    DlqMonitor auditDlqMonitor(CachingConnectionFactory connectionFactory,
                               @Value("${audit.dlq-monitor.timeout-ms:3000}") int timeoutMillis) {
        return new DlqMonitor(connectionFactory.getRabbitConnectionFactory(), DLQS, timeoutMillis);
    }
}
