package com.agricontract.contract.infrastructure.config;

import com.agricontract.contract.application.exception.ActivationRefusedException;
import com.agricontract.contract.application.exception.MilestoneFundingRefusedException;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.List;
import java.util.Map;

@Configuration
@EnableRabbit
public class RabbitMQConfig {

    public static final String DEPOSIT_LOCKED_QUEUE = "contract-svc.escrow.deposit_locked";
    public static final String DEPOSIT_LOCK_FAILED_QUEUE = "contract-svc.escrow.deposit_lock_failed";
    public static final String ACTIVATION_REFUND_COMPLETED_QUEUE = "contract-svc.escrow.activation_refund_completed";
    public static final String MILESTONE_FUNDED_QUEUE = "contract-svc.escrow.milestone_funded";
    public static final String MILESTONE_FUNDING_FAILED_QUEUE = "contract-svc.escrow.milestone_funding_failed";
    public static final String FILE_READY_QUEUE = "contract-svc.file.ready";
    public static final String FILE_FAILED_QUEUE = "contract-svc.file.failed";
    public static final String INSPECTION_REPORT_CONFIRMED_QUEUE = "contract-svc.inspection.report_confirmed";
    /** D57, #494: inspection.level1_5_assigned, on inspection-service's exchange like report_confirmed. */
    public static final String INSPECTION_LEVEL1_5_ASSIGNED_QUEUE = "contract-svc.inspection.level1_5_assigned";
    /** inspection-service's exchange (it declares the same durable topic exchange); #246 consumes its report_confirmed. */
    public static final String INSPECTION_EXCHANGE = "agricontract.inspection";
    /** file-service's own exchange for file.ready / file.failed (golden-flow-events.yaml fileReady, fileFailed). */
    public static final String FILES_EXCHANGE = "agricontract.files";

    // DlqMonitor reads the .dlq queues from this bean. agricontract.escrow.dlx is contract-service's own DLX
    // (kept from Phase 1, used by no other service).
    @Bean
    public Declarables contractDeclarables() {
        TopicExchange escrow = new TopicExchange("agricontract.escrow", true, false);
        DirectExchange dlx = new DirectExchange("agricontract.escrow.dlx", true, false);
        Queue depositLocked = QueueBuilder.durable(DEPOSIT_LOCKED_QUEUE).deadLetterExchange(dlx.getName()).build();
        Queue depositLockedDlq = QueueBuilder.durable(DEPOSIT_LOCKED_QUEUE + ".dlq").build();
        // Same name and DLX escrow-service declared ahead for it (#376), so both declarations agree.
        Queue lockFailed = QueueBuilder.durable(DEPOSIT_LOCK_FAILED_QUEUE).deadLetterExchange(dlx.getName()).build();
        Queue lockFailedDlq = QueueBuilder.durable(DEPOSIT_LOCK_FAILED_QUEUE + ".dlq").build();
        // Same name and DLX escrow-service declared ahead for it (#385).
        Queue refundCompleted = QueueBuilder.durable(ACTIVATION_REFUND_COMPLETED_QUEUE)
                .deadLetterExchange(dlx.getName()).build();
        Queue refundCompletedDlq = QueueBuilder.durable(ACTIVATION_REFUND_COMPLETED_QUEUE + ".dlq").build();
        // D64, #119. escrow-service (#120) must declare the same queues ahead before it publishes these events.
        Queue funded = QueueBuilder.durable(MILESTONE_FUNDED_QUEUE).deadLetterExchange(dlx.getName()).build();
        Queue fundedDlq = QueueBuilder.durable(MILESTONE_FUNDED_QUEUE + ".dlq").build();
        Queue fundingFailed = QueueBuilder.durable(MILESTONE_FUNDING_FAILED_QUEUE)
                .deadLetterExchange(dlx.getName()).build();
        Queue fundingFailedDlq = QueueBuilder.durable(MILESTONE_FUNDING_FAILED_QUEUE + ".dlq").build();
        // D71: file-service's scan outcomes, for the milestone evidence uploaded through contract-service. D70: the
        // exchange is file-service's, so it is not declared here; only our queues are, and FilesExchangeBinder binds
        // them once file-service has declared agricontract.files.
        Queue fileReady = QueueBuilder.durable(FILE_READY_QUEUE).deadLetterExchange(dlx.getName()).build();
        Queue fileReadyDlq = QueueBuilder.durable(FILE_READY_QUEUE + ".dlq").build();
        Queue fileFailed = QueueBuilder.durable(FILE_FAILED_QUEUE).deadLetterExchange(dlx.getName()).build();
        Queue fileFailedDlq = QueueBuilder.durable(FILE_FAILED_QUEUE + ".dlq").build();
        // #246: inspection.report_confirmed. Same exchange arguments as inspection-service's declaration.
        TopicExchange inspection = new TopicExchange(INSPECTION_EXCHANGE, true, false);
        Queue reportConfirmed = QueueBuilder.durable(INSPECTION_REPORT_CONFIRMED_QUEUE)
                .deadLetterExchange(dlx.getName()).build();
        Queue reportConfirmedDlq = QueueBuilder.durable(INSPECTION_REPORT_CONFIRMED_QUEUE + ".dlq").build();
        // #494: inspection.level1_5_assigned, same exchange and DLX convention as report_confirmed.
        Queue assigned = QueueBuilder.durable(INSPECTION_LEVEL1_5_ASSIGNED_QUEUE)
                .deadLetterExchange(dlx.getName()).build();
        Queue assignedDlq = QueueBuilder.durable(INSPECTION_LEVEL1_5_ASSIGNED_QUEUE + ".dlq").build();
        return new Declarables(List.of(
                new TopicExchange("agricontract.contracts", true, false), escrow, dlx, inspection,
                reportConfirmed, reportConfirmedDlq,
                BindingBuilder.bind(reportConfirmed).to(inspection).with("inspection.report_confirmed"),
                BindingBuilder.bind(reportConfirmedDlq).to(dlx).with("inspection.report_confirmed"),
                assigned, assignedDlq,
                BindingBuilder.bind(assigned).to(inspection).with("inspection.level1_5_assigned"),
                BindingBuilder.bind(assignedDlq).to(dlx).with("inspection.level1_5_assigned"),
                fileReady, fileReadyDlq,
                BindingBuilder.bind(fileReadyDlq).to(dlx).with("file.ready"),
                fileFailed, fileFailedDlq,
                BindingBuilder.bind(fileFailedDlq).to(dlx).with("file.failed"),
                depositLocked, depositLockedDlq,
                BindingBuilder.bind(depositLocked).to(escrow).with("escrow.deposit_locked"),
                BindingBuilder.bind(depositLockedDlq).to(dlx).with("escrow.deposit_locked"),
                lockFailed, lockFailedDlq,
                BindingBuilder.bind(lockFailed).to(escrow).with("escrow.deposit_lock_failed"),
                BindingBuilder.bind(lockFailedDlq).to(dlx).with("escrow.deposit_lock_failed"),
                refundCompleted, refundCompletedDlq,
                BindingBuilder.bind(refundCompleted).to(escrow).with("escrow.activation_refund_completed"),
                BindingBuilder.bind(refundCompletedDlq).to(dlx).with("escrow.activation_refund_completed"),
                funded, fundedDlq,
                BindingBuilder.bind(funded).to(escrow).with("escrow.milestone_funded"),
                BindingBuilder.bind(fundedDlq).to(dlx).with("escrow.milestone_funded"),
                fundingFailed, fundingFailedDlq,
                BindingBuilder.bind(fundingFailed).to(escrow).with("escrow.milestone_funding_failed"),
                BindingBuilder.bind(fundingFailedDlq).to(dlx).with("escrow.milestone_funding_failed")));
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        ObjectMapper amqpObjectMapper = objectMapper.copy();
        amqpObjectMapper.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
        return new Jackson2JsonMessageConverter(amqpObjectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAdviceChain(retryInterceptor());
        return factory;
    }

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = Map.of(
                InvalidEventPayloadException.class, false,
                // A contract that cannot be activated now will not be on a retry either (D40).
                ActivationRefusedException.class, false,
                // A funding result its milestone cannot take will not fit on a retry either.
                MilestoneFundingRefusedException.class, false
        );
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions, true, true);

        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffOptions(1000, 2, 4000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
