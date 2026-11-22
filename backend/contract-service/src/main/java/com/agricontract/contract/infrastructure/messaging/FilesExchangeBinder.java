package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.infrastructure.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D70: agricontract.files belongs to file-service, so contract-service never declares it (it would need configure
 * permission on another service's exchange). Our own queues come from {@link RabbitMQConfig}; this binds them to the
 * exchange once file-service has declared it: a passive check (no permission needed) and then queue.bind (read on the
 * exchange, write on our queue). Until then it logs a warning and tries again; after a new connection it binds again
 * (idempotent), so a broker that lost the binding gets it back.
 */
@Slf4j
@Component
public class FilesExchangeBinder implements ConnectionListener {
    private final ConnectionFactory connectionFactory;
    private volatile boolean bound;

    public FilesExchangeBinder(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        connectionFactory.addConnectionListener(this);
    }

    @Override
    public void onCreate(Connection connection) {
        bound = false;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${contract.rabbit.files-bind-retry-interval:PT30S}")
    public void bindWhenDeclared() {
        if (bound) {
            return;
        }
        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            channel.exchangeDeclarePassive(RabbitMQConfig.FILES_EXCHANGE);
            channel.queueBind(RabbitMQConfig.FILE_READY_QUEUE, RabbitMQConfig.FILES_EXCHANGE, "file.ready");
            channel.queueBind(RabbitMQConfig.FILE_FAILED_QUEUE, RabbitMQConfig.FILES_EXCHANGE, "file.failed");
            bound = true;
            log.info("event=files_exchange_bound exchange={}", RabbitMQConfig.FILES_EXCHANGE);
        } catch (Exception e) {
            // 404 on the passive check: file-service has not declared its exchange yet.
            log.warn("event=files_exchange_not_bound exchange={} reason={}", RabbitMQConfig.FILES_EXCHANGE,
                    e.getMessage());
        }
    }

    boolean isBound() {
        return bound;
    }
}
