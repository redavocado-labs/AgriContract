package com.agricontract.contract.infrastructure.milestone;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.inputMandatoryInspection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.application.service.MilestoneFunding;
import com.agricontract.contract.application.service.MilestoneReadAccess;
import com.agricontract.contract.application.dto.MilestoneDetailView;
import com.agricontract.contract.application.exception.EvidenceFileNotFoundException;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.application.usecase.AutoConfirmMilestonesUseCase;
import com.agricontract.contract.application.usecase.ConfirmMilestoneCleanUseCase;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.usecase.ReadMilestoneEvidenceUseCase;
import com.agricontract.contract.application.usecase.RecordFileScanResultUseCase;
import com.agricontract.contract.application.usecase.RecordBuyerReceiptUseCase;
import com.agricontract.contract.application.usecase.RecordSellerWeighedUseCase;
import com.agricontract.contract.application.usecase.UploadMilestoneEvidenceUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.MilestoneRequestInvalidException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SettlementPath;
import com.agricontract.contract.domain.model.milestone.BuyerReceiptConsent;
import com.agricontract.contract.domain.model.milestone.CleanReceiptConsent;
import com.agricontract.contract.domain.model.milestone.SellerWeighConsent;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.terms.ContractTemplateProfile;
import com.agricontract.contract.domain.model.terms.ContractTermsRules;
import com.agricontract.contract.domain.model.terms.ContractTermsSnapshot;
import com.agricontract.contract.domain.model.terms.TermsCanonicalizer;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.infrastructure.config.BusinessCalendarConfiguration;
import com.agricontract.contract.infrastructure.config.MilestoneDeliveryConfiguration;
import com.agricontract.contract.infrastructure.consent.ConsentConfiguration;
import com.agricontract.contract.infrastructure.contract.FrozenSchemas;
import com.agricontract.contract.infrastructure.web.dto.MilestoneDetailResponse;
import com.agricontract.contract.infrastructure.messaging.FileScanResultConsumer;
import com.agricontract.contract.infrastructure.messaging.OutboxContractEventPublisher;
import com.agricontract.contract.infrastructure.persistence.SpringTransactionRunner;
import com.agricontract.contract.infrastructure.persistence.consent.JdbcConsentEvidenceRepository;
import com.agricontract.contract.infrastructure.persistence.idempotency.JdbcIdempotencyStore;
import com.agricontract.contract.infrastructure.persistence.mapper.ContractMapper;
import com.agricontract.contract.infrastructure.persistence.milestone.JdbcMilestoneEvidenceRepository;
import com.agricontract.contract.infrastructure.persistence.milestone.JdbcMilestoneRepository;
import com.agricontract.contract.infrastructure.persistence.milestone.JdbcSellerWeighDeclarationRepository;
import com.agricontract.contract.infrastructure.persistence.repository.ContractRepositoryImpl;
import com.agricontract.contract.infrastructure.persistence.signature.JdbcSignatureRepository;
import com.agricontract.contract.infrastructure.terms.ContractTermsConfiguration;
import com.agricontract.contract.infrastructure.terms.JcsTermsCanonicalizer;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.EventingAutoConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #123 on MySQL (D77, D78): clean confirmation by the buyer and by the timeout, through the real repositories, outbox,
 * consent and V19 constraints. The seller weighs and the buyer receives first (#121, #122 code). file-service is a fake.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
        JdbcMilestoneRepository.class, OutboxContractEventPublisher.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class, MilestoneDeliveryConfiguration.class,
        JdbcMilestoneEvidenceRepository.class, JdbcSellerWeighDeclarationRepository.class,
        JdbcConsentEvidenceRepository.class, JdbcIdempotencyStore.class, UploadMilestoneEvidenceUseCase.class,
        RecordSellerWeighedUseCase.class, RecordBuyerReceiptUseCase.class, ConfirmMilestoneCleanUseCase.class,
        AutoConfirmMilestonesUseCase.class, MilestoneCleanSettlement.class, EvidenceAttachment.class, RecordFileScanResultUseCase.class,
        MilestoneReadAccess.class, GetMilestoneDetailUseCase.class, ReadMilestoneEvidenceUseCase.class,
        FileScanResultConsumer.class, CleanSettlementIntegrationTest.Fakes.class})
@Testcontainers
class CleanSettlementIntegrationTest {
    private static final String BUYER = "0b7e3b7a-1c1e-4c5e-9f0a-3d2b1a0c9e8f";
    private static final String SELLER = "5a4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /** file-service: one fileId per (user, key), PROCESSING; confirm-attached recorded, or failing on demand. */
    static class FakeFileStore implements EvidenceFileStore {
        final List<String> confirmed = new ArrayList<>();
        int stored;
        boolean confirmFails;

        @Override
        public StoredFile storeOnBehalfOf(String actingUserId, String idempotencyKey, String contentType,
                                          long contentLength, InputStream body) {
            stored++;
            return new StoredFile(UUID.nameUUIDFromBytes((actingUserId + "|" + idempotencyKey)
                    .getBytes(StandardCharsets.UTF_8)).toString(), EvidenceScanStatus.PROCESSING);
        }

        @Override
        public void confirmAttached(String fileId) {
            if (confirmFails) {
                throw new IllegalStateException("file-service down");
            }
            confirmed.add(fileId);
        }

        @Override
        public FileContent read(String fileId) {
            byte[] bytes = ("bytes of " + fileId).getBytes(StandardCharsets.UTF_8);
            return new FileContent("image/jpeg", bytes.length, new ByteArrayInputStream(bytes));
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        FakeFileStore fakeFileStore() {
            return new FakeFileStore();
        }
    }

    @Autowired UploadMilestoneEvidenceUseCase upload;
    @Autowired RecordSellerWeighedUseCase weigh;
    @Autowired RecordBuyerReceiptUseCase receive;
    @Autowired ConfirmMilestoneCleanUseCase confirm;
    @Autowired AutoConfirmMilestonesUseCase autoConfirm;
    @Autowired com.agricontract.calendar.VietnamBusinessDays calendar;
    @Autowired FileScanResultConsumer scans;
    @Autowired EvidenceAttachment attachment;
    @Autowired GetMilestoneDetailUseCase detail;
    @Autowired ReadMilestoneEvidenceUseCase readEvidence;
    @Autowired FakeFileStore fileStore;
    @Autowired MilestoneFunding funding;
    @Autowired JdbcMilestoneRepository milestones;
    @Autowired JdbcSellerWeighDeclarationRepository declarations;
    @Autowired ContractRepositoryImpl contracts;
    @Autowired TransactionRunner transactions;
    @Autowired TermsCanonicalizer canonicalizer;
    @Autowired ContractTermsRules rules;
    @Autowired ContractTemplateProfile template;
    @Autowired JdbcTemplate jdbc;

    private Contract contract;
    private Milestone first;
    private Milestone second;

    /** An ACTIVE contract whose first milestone is funded (IN_PROGRESS); the second is still CREATED. */
    @BeforeEach
    void setUp() {
        fileStore.confirmed.clear();
        fileStore.confirmFails = false;
        contract = offered();
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", id());
        Contract active = contracts.findById(contract.getContractId()).orElseThrow();
        List<Milestone> planned = transactions.inTransaction(
                () -> funding.planOnActivation(active, Instant.now(), id(), null));
        first = planned.get(0);
        second = planned.get(1);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("UPDATE milestone SET funding_status = 'LOCKED', effective_unit_price = 95000.00,"
                + " batch_amount = 902500000, funding_requested_at = ?, funding_locked_at = ?, status = 'IN_PROGRESS'"
                + " WHERE milestone_id = ?", now, now, first.milestoneId());
    }

    @Test
    void theBuyerConfirms_theMilestoneIsSettled_andEachEventIsWrittenExactlyOnce() throws Exception {
        receivedByTheBuyer(first, "9000");
        UUID key = UUID.randomUUID();

        confirm.execute(confirmCommand(first, BUYER, key));

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().path()).isEqualTo(SettlementPath.CLEAN_CONFIRMED);
        assertThat(settled.settlement().billableQuantityKg()).isEqualByComparingTo("9487.5");
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("901312500.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("1187500.00");
        assertThat(settled.settlement().settledAt()).isNotNull();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM milestone WHERE milestone_id = ?",
                first.milestoneId());
        assertThat(row).containsEntry("settlement_path", "CLEAN_CONFIRMED");
        assertThat((BigDecimal) row.get("billable_quantity_kg")).isEqualByComparingTo("9487.5");
        Map<String, Object> consent = jdbc.queryForMap("SELECT * FROM consent_evidence WHERE subject_id = ?"
                + " AND action_type = 'CONFIRM_CLEAN_RECEIPT'", first.milestoneId());
        assertThat(consent).containsEntry("subject_type", "MILESTONE").containsEntry("actor_role", "BUYER")
                .containsEntry("actor_user_id", BUYER).containsEntry("statement_code", "CONFIRM_CLEAN_RECEIPT_AS_BUYER");

        List<JsonNode> settledEvents = events("milestone.settled");
        assertThat(settledEvents).hasSize(1);
        JsonNode event = settledEvents.get(0);
        FrozenSchemas.assertEvent("milestoneSettled", event);
        JsonNode payload = event.get("payload");
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("contractId", "milestoneId",
                "lockedAmount", "actualAmount", "recipients");
        assertThat(payload.get("contractId").asText()).isEqualTo(id());
        assertThat(payload.get("milestoneId").asText()).isEqualTo(first.milestoneId());
        assertThat(payload.get("lockedAmount").asText()).isEqualTo("902500000.00");
        assertThat(payload.get("actualAmount").asText()).isEqualTo("901312500.00");
        assertThat(payload.get("recipients")).hasSize(2);
        assertThat(payload.at("/recipients/0/role").asText()).isEqualTo("BUYER");
        assertThat(payload.at("/recipients/0/userId").asText()).isEqualTo(BUYER);
        assertThat(payload.at("/recipients/0/email").asText()).isEqualTo("buyer@example.com");
        assertThat(payload.at("/recipients/1/role").asText()).isEqualTo("SELLER");
        assertThat(payload.at("/recipients/1/userId").asText()).isEqualTo(SELLER);

        List<JsonNode> confirmedEvents = events("milestone.buyer_confirmed");
        assertThat(confirmedEvents).hasSize(1);
        FrozenSchemas.assertEvent("milestoneBuyerConfirmed", confirmedEvents.get(0));
        JsonNode confirmed = confirmedEvents.get(0).get("payload");
        assertThat(confirmed.fieldNames()).toIterable().containsExactlyInAnyOrder("contractId", "milestoneId",
                "confirmedBy");
        assertThat(confirmed.get("confirmedBy").asText()).isEqualTo("BUYER");

        confirm.execute(confirmCommandWithHash(first, BUYER, key, hashOf(first)));
        assertThat(events("milestone.settled")).hasSize(1);
        assertThat(events("milestone.buyer_confirmed")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'CONFIRM_CLEAN_RECEIPT' AND subject_id = ?", Integer.class, first.milestoneId())).isEqualTo(1);
    }

    @Test
    void withinTolerance_theSellerIsPaidTheDeclaredWeight_andOverDeliveryIsCapped() {
        receivedByTheBuyer(first, "9300");
        confirm.execute(confirmCommand(first, BUYER, UUID.randomUUID()));
        assertThat(reload(first).settlement().sellerAmount().wire()).isEqualTo("902500000.00");
        assertThat(reload(first).settlement().buyerRefundAmount().wire()).isEqualTo("0.00");
    }

    @Test
    void whoMayConfirm_andWhichMilestone() {
        receivedByTheBuyer(first, "9400");

        assertThatThrownBy(() -> confirm.execute(confirmCommand(first, SELLER, UUID.randomUUID())))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> confirm.requireBuyerOfMilestone(id(), first.milestoneId(), SELLER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        Contract other = offered();
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", other.getContractId().value());
        assertThatThrownBy(() -> confirm.requireBuyerOfMilestone(other.getContractId().value(), first.milestoneId(),
                BUYER)).isInstanceOf(MilestoneNotFoundException.class);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(events("milestone.settled")).isEmpty();
    }

    @Test
    void aMilestoneNotBuyerReceived_orOfAContractNotActive_cannotBeConfirmed() {
        weighedByTheSeller(first);
        assertCode(() -> confirm.execute(confirmCommandWithHash(first, BUYER, UUID.randomUUID(), "a".repeat(64))),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);

        String photo = uploadReady(BUYER, first);
        receive.execute(receiveCommand(first, "9400", photo, null, UUID.randomUUID()));
        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE contract_id = ?", id());
        assertCode(() -> confirm.execute(confirmCommand(first, BUYER, UUID.randomUUID())),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(events("milestone.settled")).isEmpty();
    }

    @Test
    void aWrongHashOrStatement_isRefused_withNothingWritten() {
        receivedByTheBuyer(first, "9400");

        assertCode(() -> confirm.execute(confirmCommandWithHash(first, BUYER, UUID.randomUUID(), "b".repeat(64))),
                ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertCode(() -> confirm.execute(new MilestoneEvidenceCommands.Confirm(id(), first.milestoneId(), BUYER,
                        UUID.randomUUID(), "0.9", hashOf(first))),
                ContractConflictException.CONSENT_STATEMENT_OUTDATED);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(reload(first).settlement()).isNull();
        assertThat(events("milestone.settled")).isEmpty();
        assertThat(events("milestone.buyer_confirmed")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'CONFIRM_CLEAN_RECEIPT' AND subject_id = ?", Integer.class, first.milestoneId())).isZero();
    }

    @Test
    void aMandatoryInspection_blocksTheBuyer_andTheTimeout() {
        Contract mandatory = offered(inputMandatoryInspection());
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", mandatory.getContractId().value());
        Contract active = contracts.findById(mandatory.getContractId()).orElseThrow();
        List<Milestone> planned = transactions.inTransaction(
                () -> funding.planOnActivation(active, Instant.now(), mandatory.getContractId().value(), null));
        Milestone m = planned.get(0);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("UPDATE milestone SET funding_status = 'LOCKED', effective_unit_price = 95000.00,"
                + " batch_amount = 902500000, funding_requested_at = ?, funding_locked_at = ?, status = 'IN_PROGRESS'"
                + " WHERE milestone_id = ?", now, now, m.milestoneId());
        Contract previous = contract;
        contract = mandatory;
        String sellerPhoto = uploadReady(SELLER, m);
        weigh.execute(weighCommand(m, "9500", sellerPhoto, null, UUID.randomUUID()));
        receive.execute(receiveCommand(m, "9400", uploadReady(BUYER, m), null, UUID.randomUUID()));
        jdbc.update("UPDATE milestone SET buyer_received_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), m.milestoneId());

        assertCode(() -> confirm.execute(confirmCommand(m, BUYER, UUID.randomUUID())),
                ContractConflictException.INSPECTION_REPORT_PENDING);
        assertThat(autoConfirm.execute()).isZero();

        assertThat(reload(m).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE event_type = 'milestone.settled'"
                + " AND aggregate_id = ?", Integer.class, m.milestoneId())).isZero();
        contract = previous;
    }

    @Test
    void afterTheWindow_theTimeoutSettlesOnce_withSystemTimeout_andNoConsent() throws Exception {
        receivedByTheBuyer(first, "9000");
        jdbc.update("UPDATE milestone SET buyer_received_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), first.milestoneId());

        assertThat(autoConfirm.execute()).isEqualTo(1);

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().path()).isEqualTo(SettlementPath.CLEAN_AUTO_CONFIRMED);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("901312500.00");
        List<JsonNode> confirmed = events("milestone.buyer_confirmed");
        assertThat(confirmed).hasSize(1);
        FrozenSchemas.assertEvent("milestoneBuyerConfirmed", confirmed.get(0));
        assertThat(confirmed.get(0).at("/payload/confirmedBy").asText()).isEqualTo("SYSTEM_TIMEOUT");
        assertThat(events("milestone.settled")).hasSize(1);
        FrozenSchemas.assertEvent("milestoneSettled", events("milestone.settled").get(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'CONFIRM_CLEAN_RECEIPT' AND subject_id = ?", Integer.class, first.milestoneId())).isZero();

        assertThat(autoConfirm.execute()).isZero();
        assertThat(events("milestone.settled")).hasSize(1);
        assertThat(events("milestone.buyer_confirmed")).hasSize(1);
    }

    @Test
    void beforeTheWindowCloses_theTimeoutLeavesTheMilestoneAlone_andALateConfirmIsStillAccepted() {
        receivedByTheBuyer(first, "9400");

        assertThat(autoConfirm.execute()).isZero();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(events("milestone.settled")).isEmpty();

        jdbc.update("UPDATE milestone SET buyer_received_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), first.milestoneId());
        confirm.execute(confirmCommand(first, BUYER, UUID.randomUUID()));

        assertThat(reload(first).settlement().path()).isEqualTo(SettlementPath.CLEAN_CONFIRMED);
        assertThat(events("milestone.buyer_confirmed").get(0).at("/payload/confirmedBy").asText()).isEqualTo("BUYER");
        assertThat(autoConfirm.execute()).isZero();
        assertThat(events("milestone.settled")).hasSize(1);
    }

    @Test
    void theDetail_showsTheSettlementOnlyOnceSettled() {
        receivedByTheBuyer(first, "9000");
        assertThat(MilestoneDetailResponse.from(detail.execute(id(), first.milestoneId(), BUYER,
                java.util.Set.of("BUYER"))).settlement()).isNull();

        confirm.execute(confirmCommand(first, BUYER, UUID.randomUUID()));

        MilestoneDetailResponse response = MilestoneDetailResponse.from(detail.execute(id(), first.milestoneId(),
                SELLER, java.util.Set.of("SELLER")));
        assertThat(response.milestone().status()).isEqualTo("SETTLED");
        assertThat(response.settlement().path()).isEqualTo("CLEAN_CONFIRMED");
        assertThat(response.settlement().billableQuantityKg()).isEqualByComparingTo("9487.5");
        assertThat(response.settlement().sellerAmount()).isEqualTo("901312500.00");
        assertThat(response.settlement().buyerRefundAmount()).isEqualTo("1187500.00");
        assertThat(response.settlement().settledAt()).isNotNull();
    }

    /** The seller declares 9500 kg (the batch is 9500 x 95,000 = 902,500,000), the buyer receives the given weight. */
    private void receivedByTheBuyer(Milestone milestone, String buyerKg) {
        weighedByTheSeller(milestone);
        String photo = uploadReady(BUYER, milestone);
        receive.execute(receiveCommand(milestone, buyerKg, photo, null, UUID.randomUUID()));
    }

    private MilestoneEvidenceCommands.Confirm confirmCommand(Milestone milestone, String actor, UUID key) {
        return confirmCommandWithHash(milestone, actor, key, hashOf(milestone));
    }

    private MilestoneEvidenceCommands.Confirm confirmCommandWithHash(Milestone milestone, String actor, UUID key,
                                                                    String hash) {
        return new MilestoneEvidenceCommands.Confirm(milestone.contractId(), milestone.milestoneId(), actor, key,
                "1.0", hash);
    }

    private String hashOf(Milestone milestone) {
        Milestone current = reload(milestone);
        return CleanReceiptConsent.contentHash(current.milestoneId(), current.sellerWeighing().declarationCount(),
                current.sellerWeighing().declaredWeight(), current.buyerReceipt().receivedWeight());
    }

    private List<JsonNode> events(String type) {
        return jdbc.queryForList("SELECT envelope FROM event_outbox WHERE event_type = ? AND aggregate_id = ?",
                String.class, type, first.milestoneId()).stream().map(json -> {
                    try {
                        return new ObjectMapper().readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    // ---- helpers ----

    /** The seller weighs 9500 kg with a ready photo; returns the photo's id. */
    private String weighedByTheSeller(Milestone milestone) {
        String photo = uploadReady(SELLER, milestone);
        weigh.execute(weighCommand(milestone, "9500", photo, null, UUID.randomUUID()));
        return photo;
    }

    private MilestoneEvidenceCommands.Receive receiveCommand(Milestone milestone, String weight, String photo,
                                                             String certificate, UUID key) {
        return receiveCommand(milestone, BUYER, weight, photo, certificate, key);
    }

    private MilestoneEvidenceCommands.Receive receiveCommand(Milestone milestone, String actor, String weight,
                                                             String photo, String certificate, UUID key) {
        BigDecimal kg = new BigDecimal(weight);
        return new MilestoneEvidenceCommands.Receive(id(), milestone.milestoneId(), actor, key, kg, photo,
                certificate, "1.0", BuyerReceiptConsent.contentHash(milestone.milestoneId(), kg, photo, certificate));
    }

    private List<JsonNode> receivedEvents() {
        return jdbc.queryForList("SELECT envelope FROM event_outbox WHERE event_type = 'milestone.buyer_received'"
                + " AND aggregate_id = ?", String.class, first.milestoneId()).stream().map(json -> {
                    try {
                        return new ObjectMapper().readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    private String uploadReady(String actor, Milestone milestone) {
        String fileId = uploadFile(actor, milestone, UUID.randomUUID()).fileId();
        scans.onReady(message(ready(fileId)));
        return fileId;
    }

    private MilestoneEvidenceCommands.Uploaded uploadFile(String actor, Milestone milestone, UUID key) {
        byte[] bytes = "jpeg".getBytes(StandardCharsets.UTF_8);
        return upload.execute(new MilestoneEvidenceCommands.Upload(id(), milestone.milestoneId(), actor, key,
                "image/jpeg", bytes.length), new ByteArrayInputStream(bytes));
    }

    private MilestoneEvidenceCommands.Weigh weighCommand(Milestone milestone, String weight, String photo,
                                                         String certificate, UUID key) {
        return weighCommand(milestone, SELLER, weight, photo, certificate, key);
    }

    private MilestoneEvidenceCommands.Weigh weighCommand(Milestone milestone, String actor, String weight,
                                                         String photo, String certificate, UUID key) {
        BigDecimal kg = new BigDecimal(weight);
        return new MilestoneEvidenceCommands.Weigh(id(), milestone.milestoneId(), actor, key, kg, photo, certificate,
                "1.0", SellerWeighConsent.contentHash(milestone.milestoneId(), kg, photo, certificate));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).satisfies(e -> assertThat(e instanceof ContractConflictException conflict
                ? conflict.getCode() : ((MilestoneRequestInvalidException) e).getCode()).isEqualTo(code));
    }

    private Milestone reload(Milestone milestone) {
        return milestones.findById(milestone.milestoneId()).orElseThrow();
    }

    private EventEnvelope ready(String fileId) {
        return envelope("file.ready", readyPayload(fileId));
    }

    private EventEnvelope failed(String fileId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", fileId);
        payload.put("uploadedBy", SELLER);
        payload.put("ingestChannel", "DIRECT_UPLOAD");
        payload.put("failureReason", "EICAR test signature");
        return envelope("file.failed", payload);
    }

    private static Map<String, Object> readyPayload(String fileId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", fileId);
        payload.put("uploadedBy", SELLER);
        payload.put("ingestChannel", "DIRECT_UPLOAD");
        payload.put("contentType", "image/jpeg");
        payload.put("fileSize", 4);
        return payload;
    }

    private static EventEnvelope envelope(String eventType, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, 1, Instant.now(), "file-service",
                payload.get("fileId").toString(), null, null, null, payload);
    }

    private static Message message(EventEnvelope envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(new EventEnvelopeCodec().toJson(envelope).getBytes(), properties);
    }

    private List<JsonNode> weighedEvents() {
        return jdbc.queryForList("SELECT envelope FROM event_outbox WHERE event_type = 'milestone.seller_weighed'"
                + " AND aggregate_id = ?", String.class, first.milestoneId()).stream().map(json -> {
                    try {
                        return new ObjectMapper().readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    private String id() {
        return contract.getContractId().value();
    }

    private Contract offered() {
        return offered(input());
    }

    private Contract offered(com.agricontract.contract.domain.model.terms.ContractTermsInput negotiated) {
        CanonicalTerms terms = CanonicalTerms.of(ContractTermsSnapshot.assemble(negotiated, goods(), template, rules),
                canonicalizer);
        Contract offered = Contract.offerCoffee(new ContractId(UUID.randomUUID().toString()),
                goods().listingId().toString(), BUYER, SELLER, "HTX Mua", "HTX Bán", "buyer@example.com",
                "seller@example.com", terms);
        contracts.save(offered);
        return offered;
    }
}
