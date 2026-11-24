package com.agricontract.contract.infrastructure.messaging;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.application.dto.RetryRemedyLegsCommand;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.exception.TerminalCommitRefusedException;
import com.agricontract.contract.application.usecase.CommitTerminalAfterLockClearedUseCase;
import com.agricontract.contract.application.usecase.RetryRemedyLegsUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.terms.ContractTemplateProfile;
import com.agricontract.contract.domain.model.terms.ContractTermsRules;
import com.agricontract.contract.domain.model.terms.ContractTermsSnapshot;
import com.agricontract.contract.domain.model.terms.TermsCanonicalizer;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.infrastructure.config.BusinessCalendarConfiguration;
import com.agricontract.contract.infrastructure.consent.ConsentConfiguration;
import com.agricontract.contract.infrastructure.contract.FrozenSchemas;
import com.agricontract.contract.infrastructure.persistence.SpringTransactionRunner;
import com.agricontract.contract.infrastructure.persistence.idempotency.JdbcIdempotencyStore;
import com.agricontract.contract.infrastructure.persistence.mapper.ContractMapper;
import com.agricontract.contract.infrastructure.persistence.repository.ContractRepositoryImpl;
import com.agricontract.contract.infrastructure.persistence.signature.JdbcSignatureRepository;
import com.agricontract.contract.infrastructure.terms.ContractTermsConfiguration;
import com.agricontract.contract.infrastructure.terms.JcsTermsCanonicalizer;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.EventingAutoConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #139, D93 on real MySQL: escrow.contract_lock_cleared commits SETTLED only for the pending decision, and OPERATOR
 * RetryRemedyLegs writes contract.remedy_retry_requested.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({SpringTransactionRunner.class, ContractLockClearedConsumer.class,
        CommitTerminalAfterLockClearedUseCase.class, RetryRemedyLegsUseCase.class, OutboxContractEventPublisher.class,
        JdbcIdempotencyStore.class, BusinessCalendarConfiguration.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class})
@Testcontainers
class ContractTerminalGuardIntegrationTest {
    private static final String BUYER = "0b7e3b7a-1c1e-4c5e-9f0a-3d2b1a0c9e8f";
    private static final String SELLER = "5a4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a";
    private static final String OPERATOR = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a";
    private static final String D = "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
    private static final String X = "0f1e2d3c-4b5a-4968-8776-655443322110";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ContractLockClearedConsumer consumer;
    @Autowired RetryRemedyLegsUseCase retry;
    @Autowired ContractRepositoryImpl contracts;
    @Autowired TermsCanonicalizer canonicalizer;
    @Autowired ContractTermsRules rules;
    @Autowired ContractTemplateProfile template;
    @Autowired JdbcTemplate jdbc;

    private Contract contract;

    @BeforeEach
    void setUp() {
        contract = offered();
        setStatus(ContractStatus.ACTIVE);
    }

    @Test
    void pendingDecisionAmongTheCleared_settlesTheContract_withOneContractSettled() throws Exception {
        awaitSettled(D);
        EventEnvelope cleared = lockCleared(List.of(X, D));

        consumer.onLockCleared(message(cleared));

        Contract settled = load();
        assertThat(settled.getStatus()).isEqualTo(ContractStatus.SETTLED);
        assertThat(settled.getSettledAt()).isNotNull();
        assertThat(settled.getPendingTerminalDecisionId()).as("kept as evidence").isEqualTo(D);
        List<JsonNode> events = outbox("contract.settled");
        assertThat(events).hasSize(1);
        FrozenSchemas.assertEvent("contractSettled", events.get(0));
        assertThat(events.get(0).at("/payload/buyerId").asText()).isEqualTo(BUYER);
        assertThat(events.get(0).at("/payload/sellerId").asText()).isEqualTo(SELLER);
        assertThat(events.get(0).get("causationId").asText()).isEqualTo(cleared.eventId().toString());
    }

    @Test
    void sameEventAgain_doesNothing_andANewEventOnASettledContractIsANoOp() throws Exception {
        awaitSettled(D);
        EventEnvelope cleared = lockCleared(List.of(D));
        consumer.onLockCleared(message(cleared));

        EventEnvelope later = lockCleared(List.of(D));
        consumer.onLockCleared(message(later));

        assertThat(load().getStatus()).isEqualTo(ContractStatus.SETTLED);
        assertThat(outbox("contract.settled")).hasSize(1);
        assertThat(claimed(later)).as("the new event is claimed and acked as NOT_ACTIVE").isTrue();
    }

    @Test
    void pendingDecisionNotCleared_orNothingPending_leavesTheContractActive() throws Exception {
        consumer.onLockCleared(message(lockCleared(List.of(D))));
        assertThat(load().getStatus()).as("nothing pending").isEqualTo(ContractStatus.ACTIVE);

        awaitSettled(D);
        consumer.onLockCleared(message(lockCleared(List.of(X))));

        Contract active = load();
        assertThat(active.getStatus()).as("D not cleared yet").isEqualTo(ContractStatus.ACTIVE);
        assertThat(active.getSettledAt()).isNull();
        assertThat(outbox("contract.settled")).isEmpty();
    }

    @Test
    void unknownContract_isRefused_andNotClaimed() {
        EventEnvelope cleared = envelope(payload(UUID.randomUUID().toString(), List.of(D)));

        assertThatThrownBy(() -> consumer.onLockCleared(message(cleared)))
                .isInstanceOf(TerminalCommitRefusedException.class);
        assertThat(claimed(cleared)).as("rolled back with the refusal").isFalse();
    }

    @Test
    void payloadOutsideTheSchema_isRefused_beforeAnything() {
        Map<String, Object> missing = payload(id(), List.of(D));
        missing.remove("clearedAt");
        Map<String, Object> extra = payload(id(), List.of(D));
        extra.put("remainingLock", "0.00");
        Map<String, Object> empty = payload(id(), List.of());
        Map<String, Object> badUuid = payload(id(), List.of("not-a-uuid"));
        Map<String, Object> duplicate = payload(id(), List.of(D, D.toUpperCase()));
        Map<String, Object> badContract = payload("contract-1", List.of(D));
        Map<String, Object> badDate = payload(id(), List.of(D));
        badDate.put("clearedAt", "yesterday");
        List<EventEnvelope> bad = new ArrayList<>();
        for (Map<String, Object> payload : List.of(missing, extra, empty, badUuid, duplicate, badContract, badDate)) {
            bad.add(envelope(payload));
        }
        bad.add(new EventEnvelope(UUID.randomUUID(), "escrow.contract_lock_cleared", 1, Instant.now(),
                "bank-service", id(), id(), null, null, payload(id(), List.of(D))));
        for (EventEnvelope envelope : bad) {
            assertThatThrownBy(() -> consumer.onLockCleared(message(envelope)))
                    .as(envelope.payload().toString()).isInstanceOf(InvalidEventPayloadException.class);
            assertThat(claimed(envelope)).isFalse();
        }
        assertThat(load().getStatus()).isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void operatorRetry_writesOneRetryRequest_andAReplayWritesNothing() throws Exception {
        UUID key = UUID.randomUUID();

        retry.execute(new RetryRemedyLegsCommand(id(), OPERATOR, key));
        retry.execute(new RetryRemedyLegsCommand(id(), OPERATOR, key));

        List<JsonNode> requests = outbox("contract.remedy_retry_requested");
        assertThat(requests).hasSize(1);
        FrozenSchemas.assertEvent("contractRemedyRetryRequested", requests.get(0));
        assertThat(requests.get(0).at("/payload/operatorUserId").asText()).isEqualTo(OPERATOR);
        assertThat(load().getStatus()).as("a retry changes nothing on the contract").isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void retry_onAnUnknownContract_is404_andOnAContractNotActive_is409() {
        assertThatThrownBy(() -> retry.execute(new RetryRemedyLegsCommand(UUID.randomUUID().toString(), OPERATOR,
                UUID.randomUUID()))).isInstanceOf(ContractNotFoundException.class);

        setStatus(ContractStatus.SIGNED);
        assertThatThrownBy(() -> retry.execute(new RetryRemedyLegsCommand(id(), OPERATOR, UUID.randomUUID())))
                .isInstanceOf(ContractConflictException.class)
                .hasFieldOrPropertyWithValue("code", ContractConflictException.REMEDY_RETRY_NOT_ALLOWED);
        assertThat(outbox("contract.remedy_retry_requested")).isEmpty();
    }

    private void awaitSettled(String decisionId) {
        Contract active = load();
        active.awaitTerminal(decisionId, ContractStatus.SETTLED);
        contracts.save(active);
    }

    private EventEnvelope lockCleared(List<String> decisionIds) {
        return envelope(payload(id(), decisionIds));
    }

    private Map<String, Object> payload(String contractId, List<String> decisionIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("remedyDecisionIds", decisionIds);
        payload.put("clearedAt", Instant.now().toString());
        return payload;
    }

    private EventEnvelope envelope(Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), "escrow.contract_lock_cleared", 1, Instant.now(),
                "escrow-service", id(), id(), UUID.randomUUID().toString(), null, payload);
    }

    private static Message message(EventEnvelope envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(new EventEnvelopeCodec().toJson(envelope).getBytes(), properties);
    }

    private List<JsonNode> outbox(String eventType) {
        return jdbc.queryForList("SELECT envelope FROM event_outbox WHERE event_type = ? AND aggregate_id = ?"
                        + " ORDER BY created_at", String.class, eventType, id()).stream()
                .map(json -> {
                    try {
                        return new ObjectMapper().readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    private boolean claimed(EventEnvelope envelope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_name = ? AND event_id = ?",
                Integer.class, ContractLockClearedConsumer.CONSUMER_NAME, envelope.eventId().toString()) == 1;
    }

    private String id() {
        return contract.getContractId().value();
    }

    private void setStatus(ContractStatus status) {
        jdbc.update("UPDATE contracts SET status = ? WHERE contract_id = ?", status.name(), id());
    }

    private Contract load() {
        return contracts.findById(contract.getContractId()).orElseThrow();
    }

    private Contract offered() {
        CanonicalTerms terms = CanonicalTerms.of(ContractTermsSnapshot.assemble(input(), goods(), template, rules),
                canonicalizer);
        Contract offered = Contract.offerCoffee(new ContractId(UUID.randomUUID().toString()),
                goods().listingId().toString(), BUYER, SELLER, "HTX Mua", "HTX Bán", "buyer@example.com",
                "seller@example.com", terms);
        contracts.save(offered);
        return offered;
    }
}
