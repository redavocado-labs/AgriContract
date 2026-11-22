package com.agricontract.contract.infrastructure.milestone;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.application.service.MilestoneFunding;
import com.agricontract.contract.application.service.MilestoneReadAccess;
import com.agricontract.contract.application.dto.MilestoneDetailView;
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
import com.agricontract.contract.domain.model.milestone.BuyerReceiptConsent;
import com.agricontract.contract.domain.model.milestone.SellerWeighConsent;
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
import com.agricontract.contract.infrastructure.persistence.inspection.JdbcInspectionReportRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #122 on MySQL (D76): the buyer's receipt, through the real repositories, outbox, consent and V18 constraints.
 * The seller weighs first (#121 code). file-service is a fake.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
        JdbcMilestoneRepository.class, JdbcInspectionReportRepository.class, OutboxContractEventPublisher.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class, MilestoneDeliveryConfiguration.class,
        JdbcMilestoneEvidenceRepository.class, JdbcSellerWeighDeclarationRepository.class,
        JdbcConsentEvidenceRepository.class, JdbcIdempotencyStore.class, UploadMilestoneEvidenceUseCase.class,
        RecordSellerWeighedUseCase.class, RecordBuyerReceiptUseCase.class, EvidenceAttachment.class, RecordFileScanResultUseCase.class,
        MilestoneReadAccess.class, GetMilestoneDetailUseCase.class, ReadMilestoneEvidenceUseCase.class,
        FileScanResultConsumer.class, BuyerReceiptIntegrationTest.Fakes.class})
@Testcontainers
class BuyerReceiptIntegrationTest {
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
                + " batch_amount = 95000000, funding_requested_at = ?, funding_locked_at = ?, status = 'IN_PROGRESS'"
                + " WHERE milestone_id = ?", now, now, first.milestoneId());
    }

    @Test
    void theBuyerRecordsReceipt_andTheReceiptIsPersisted_consented_andPublishedOnce() throws Exception {
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        String certificate = uploadReady(BUYER, first);

        receive.execute(receiveCommand(first, "9400.250", photo, certificate, UUID.randomUUID()));

        Milestone received = reload(first);
        assertThat(received.status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(received.buyerReceipt().receivedWeight()).isEqualByComparingTo("9400.25");
        assertThat(received.buyerReceipt().evidenceFileId()).isEqualTo(photo);
        assertThat(received.buyerReceipt().certificateFileId()).isEqualTo(certificate);
        assertThat(received.buyerReceipt().receivedAt()).isNotNull();
        assertThat(received.sellerWeighing().declarationCount()).isEqualTo(1);
        Map<String, Object> consent = jdbc.queryForMap("SELECT * FROM consent_evidence WHERE subject_id = ?"
                + " AND action_type = 'RECORD_BUYER_RECEIPT'", first.milestoneId());
        assertThat(consent).containsEntry("subject_type", "MILESTONE").containsEntry("actor_role", "BUYER")
                .containsEntry("actor_user_id", BUYER).containsEntry("statement_code", "RECORD_BUYER_RECEIPT_AS_BUYER");

        List<JsonNode> events = receivedEvents();
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        FrozenSchemas.assertEvent("milestoneBuyerReceived", event);
        assertThat(event.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder("contractId",
                "milestoneId", "buyerReceivedWeight", "buyerEvidenceFileId", "deliveryCertificateFileId",
                "receivedAt");
        assertThat(event.at("/payload/buyerReceivedWeight").decimalValue()).isEqualByComparingTo("9400.25");
        assertThat(event.at("/payload/buyerEvidenceFileId").asText()).isEqualTo(photo);
        assertThat(event.get("aggregateId").asText()).isEqualTo(first.milestoneId());
        assertThat(event.get("correlationId").asText()).isEqualTo(id());
        assertThat(fileStore.confirmed).contains(photo, certificate);
    }

    @Test
    void withoutACertificate_theEventLeavesItOut_andTheWeightMayExceedTheSellers() throws Exception {
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);

        receive.execute(receiveCommand(first, "10001", photo, null, UUID.randomUUID()));

        JsonNode event = receivedEvents().get(0);
        FrozenSchemas.assertEvent("milestoneBuyerReceived", event);
        assertThat(event.get("payload").has("deliveryCertificateFileId")).isFalse();
        assertThat(reload(first).buyerReceipt().certificateFileId()).isNull();
    }

    @Test
    void aRepeatedKey_isANoOp_aNewKeyIsRefused_andADifferentBodyWithTheSameKeyConflicts() throws Exception {
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        String other = uploadReady(BUYER, first);
        UUID key = UUID.randomUUID();
        receive.execute(receiveCommand(first, "9400", photo, null, key));

        receive.execute(receiveCommand(first, "9400", photo, null, key));
        assertThat(receivedEvents()).hasSize(1);

        assertThatThrownBy(() -> receive.execute(receiveCommand(first, "9300", photo, null, key)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertCode(() -> receive.execute(receiveCommand(first, "9300", other, null, UUID.randomUUID())),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(receivedEvents()).hasSize(1);
        assertThat(reload(first).buyerReceipt().receivedWeight()).isEqualByComparingTo("9400");
    }

    @Test
    void receiptClosesTheSellersCorrection_evenInsideTheWindow() {
        String sellerPhoto = weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        receive.execute(receiveCommand(first, "9400", photo, null, UUID.randomUUID()));

        assertCode(() -> weigh.execute(weighCommand(first, "9450", sellerPhoto, null, UUID.randomUUID())),
                ContractConflictException.WEIGH_CORRECTION_CLOSED);
        assertThat(reload(first).sellerWeighing().declaredWeight()).isEqualByComparingTo("9500");
    }

    @Test
    void whoMayReceive_andWhichMilestone() {
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);

        assertThatThrownBy(() -> receive.execute(receiveCommand(first, SELLER, "9400", photo, null,
                UUID.randomUUID()))).isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> receive.requireBuyerOfMilestone(id(), first.milestoneId(), SELLER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        String stranger = UUID.randomUUID().toString();
        assertThatThrownBy(() -> receive.execute(receiveCommand(first, stranger, "9400", photo, null,
                UUID.randomUUID()))).as("buyer of another contract").isInstanceOf(
                UnauthorizedContractAccessException.class);

        Contract other = offered();
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", other.getContractId().value());
        MilestoneEvidenceCommands.Receive wrongContract = new MilestoneEvidenceCommands.Receive(
                other.getContractId().value(), first.milestoneId(), BUYER, UUID.randomUUID(), new BigDecimal("9400"),
                photo, null, "1.0", BuyerReceiptConsent.contentHash(first.milestoneId(), new BigDecimal("9400"),
                        photo, null));
        assertThatThrownBy(() -> receive.execute(wrongContract)).as("milestone of another contract")
                .isInstanceOf(MilestoneNotFoundException.class);
        assertThatThrownBy(() -> receive.requireBuyerOfMilestone(other.getContractId().value(), first.milestoneId(),
                BUYER)).isInstanceOf(MilestoneNotFoundException.class);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);
    }

    @Test
    void theFilesMustBeTheBuyersOwn_andReady() {
        String sellerPhoto = weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        String scanning = uploadFile(BUYER, first, UUID.randomUUID()).fileId();

        assertCode(() -> receive.execute(receiveCommand(first, "9400", sellerPhoto, null, UUID.randomUUID())),
                MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED);
        assertCode(() -> receive.execute(receiveCommand(first, "9400", photo, photo, UUID.randomUUID())),
                MilestoneRequestInvalidException.EVIDENCE_FILE_REUSED);
        assertCode(() -> receive.execute(receiveCommand(first, "9400", scanning, null, UUID.randomUUID())),
                ContractConflictException.EVIDENCE_NOT_READY);
        String rejected = uploadFile(BUYER, first, UUID.randomUUID()).fileId();
        scans.onFailed(message(failed(rejected)));
        assertCode(() -> receive.execute(receiveCommand(first, "9400", rejected, null, UUID.randomUUID())),
                ContractConflictException.EVIDENCE_REJECTED);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);
        assertThat(receivedEvents()).isEmpty();
    }

    @Test
    void aWrongHashOrStatement_isRefused_withNothingPersisted() {
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        BigDecimal kg = new BigDecimal("9400");

        assertCode(() -> receive.execute(new MilestoneEvidenceCommands.Receive(id(), first.milestoneId(), BUYER,
                        UUID.randomUUID(), kg, photo, null, "1.0",
                        BuyerReceiptConsent.contentHash(first.milestoneId(), new BigDecimal("9401"), photo, null))),
                ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertCode(() -> receive.execute(new MilestoneEvidenceCommands.Receive(id(), first.milestoneId(), BUYER,
                        UUID.randomUUID(), kg, photo, null, "0.9",
                        BuyerReceiptConsent.contentHash(first.milestoneId(), kg, photo, null))),
                ContractConflictException.CONSENT_STATEMENT_OUTDATED);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);
        assertThat(reload(first).buyerReceipt()).isNull();
        assertThat(receivedEvents()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'RECORD_BUYER_RECEIPT' AND subject_id = ?", Integer.class, first.milestoneId())).isZero();
    }

    @Test
    void aMilestoneNotSellerWeighed_orOfAContractNotActive_cannotBeReceived() {
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        jdbc.update("UPDATE milestone SET status = 'CONTESTED' WHERE milestone_id = ?", first.milestoneId());
        assertCode(() -> receive.execute(receiveCommand(first, "9400", photo, null, UUID.randomUUID())),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);

        jdbc.update("UPDATE milestone SET status = 'SELLER_WEIGHED' WHERE milestone_id = ?", first.milestoneId());
        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE contract_id = ?", id());
        assertCode(() -> receive.execute(receiveCommand(first, "9400", photo, null, UUID.randomUUID())),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(reload(first).buyerReceipt()).isNull();
    }

    @Test
    void theDetail_showsTheReceipt_withTheConfirmWindowInBusinessDays() {
        assertThat(detail.execute(id(), first.milestoneId(), BUYER, java.util.Set.of("BUYER")).buyerReceipt())
                .isNull();
        weighedByTheSeller(first);
        String photo = uploadReady(BUYER, first);
        receive.execute(receiveCommand(first, "9400", photo, null, UUID.randomUUID()));

        MilestoneDetailView view = detail.execute(id(), first.milestoneId(), SELLER, java.util.Set.of("SELLER"));

        Instant receivedAt = reload(first).buyerReceipt().receivedAt();
        int days = contracts.findById(contract.getContractId()).orElseThrow().getCoffeeTerms().snapshot().negotiated()
                .buyerConfirmWindowDays();
        assertThat(view.buyerReceipt().receipt().receivedWeight()).isEqualByComparingTo("9400");
        assertThat(view.buyerReceipt().receipt().receivedAt()).isEqualTo(receivedAt);
        assertThat(view.buyerReceipt().confirmWindowClosesAt()).isEqualTo(calendar.deadline(receivedAt, days));
        assertThat(view.buyerReceipt().confirmWindowClosesAt()).isAfter(receivedAt);
        assertThat(MilestoneDetailResponse.from(view).buyerReceipt().deliveryCertificateFileId()).isNull();
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

    private String id() {
        return contract.getContractId().value();
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
