package com.agricontract.contract.infrastructure.milestone;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
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
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.usecase.ReadMilestoneEvidenceUseCase;
import com.agricontract.contract.application.usecase.RecordFileScanResultUseCase;
import com.agricontract.contract.application.usecase.RecordSellerWeighedUseCase;
import com.agricontract.contract.application.usecase.UploadMilestoneEvidenceUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.MilestoneRequestInvalidException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
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
 * #121 on MySQL (D71-D75): evidence upload, the file scan outcomes, the seller's weigh and its corrections, through
 * the real repositories, outbox, consent and V17 constraints. file-service is a fake.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
        JdbcMilestoneRepository.class, JdbcInspectionReportRepository.class, com.agricontract.contract.infrastructure.persistence.breach.JdbcBreachCaseRepository.class, OutboxContractEventPublisher.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class, MilestoneDeliveryConfiguration.class,
        JdbcMilestoneEvidenceRepository.class, JdbcSellerWeighDeclarationRepository.class,
        JdbcConsentEvidenceRepository.class, JdbcIdempotencyStore.class, UploadMilestoneEvidenceUseCase.class,
        RecordSellerWeighedUseCase.class, EvidenceAttachment.class, RecordFileScanResultUseCase.class,
        MilestoneReadAccess.class, GetMilestoneDetailUseCase.class, ReadMilestoneEvidenceUseCase.class,
        FileScanResultConsumer.class, SellerWeighIntegrationTest.Fakes.class})
@Testcontainers
class SellerWeighIntegrationTest {
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
    void theSellerWeighs_withAScannedPhoto_andTheDeclarationIsPublished() throws Exception {
        String photo = uploadReady(SELLER, first);
        String certificate = uploadReady(SELLER, first);

        weigh.execute(weighCommand(first, "9500.500", photo, certificate, UUID.randomUUID()));

        Milestone weighed = reload(first);
        assertThat(weighed.status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);
        assertThat(weighed.sellerWeighing().declaredWeight()).isEqualByComparingTo("9500.5");
        assertThat(weighed.sellerWeighing().declarationCount()).isEqualTo(1);
        List<SellerWeighDeclaration> history = declarations.findByMilestoneId(first.milestoneId());
        assertThat(history).singleElement().satisfies(d -> {
            assertThat(d.declarationNumber()).isEqualTo(1);
            assertThat(d.evidenceFileId()).isEqualTo(photo);
            assertThat(d.deliveryCertificateFileId()).isEqualTo(certificate);
        });
        Map<String, Object> consent = jdbc.queryForMap("SELECT * FROM consent_evidence WHERE consent_id = ?",
                history.get(0).consentId().toString());
        assertThat(consent).containsEntry("action_type", "DECLARE_SELLER_WEIGHT")
                .containsEntry("subject_type", "MILESTONE").containsEntry("subject_id", first.milestoneId())
                .containsEntry("actor_role", "SELLER")
                .containsEntry("statement_code", "DECLARE_SELLER_WEIGHT_AS_SELLER");

        List<JsonNode> events = weighedEvents();
        assertThat(events).hasSize(1);
        FrozenSchemas.assertEvent("milestoneSellerWeighed", events.get(0));
        assertThat(events.get(0).at("/payload/declarationNumber").asInt()).isEqualTo(1);
        assertThat(events.get(0).at("/payload/sellerDeclaredWeight").decimalValue()).isEqualByComparingTo("9500.5");
        assertThat(events.get(0).at("/payload/deliveryCertificateFileId").asText()).isEqualTo(certificate);
        assertThat(events.get(0).get("aggregateId").asText()).isEqualTo(first.milestoneId());
        assertThat(events.get(0).get("correlationId").asText()).isEqualTo(id());

        assertThat(fileStore.confirmed).containsExactlyInAnyOrder(photo, certificate);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM milestone_evidence_file WHERE milestone_id = ?"
                + " AND attached_confirmed_at IS NOT NULL", Integer.class, first.milestoneId())).isEqualTo(2);
    }

    @Test
    void withoutACertificate_theEventLeavesItOut() throws Exception {
        String photo = uploadReady(SELLER, first);

        weigh.execute(weighCommand(first, "1000", photo, null, UUID.randomUUID()));

        JsonNode event = weighedEvents().get(0);
        FrozenSchemas.assertEvent("milestoneSellerWeighed", event);
        assertThat(event.get("payload").has("deliveryCertificateFileId")).isFalse();
        assertThat(event.at("/payload/sellerDeclaredWeight").toString()).isEqualTo("1000");
    }

    @Test
    void aCorrection_insideTheWindow_isANewDeclaration_andTheFirstOneStays() throws Exception {
        String photo = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9500", photo, null, UUID.randomUUID()));
        Instant firstAt = reload(first).sellerWeighing().firstDeclaredAt();
        String newPhoto = uploadReady(SELLER, first);

        weigh.execute(weighCommand(first, "9450.25", newPhoto, null, UUID.randomUUID()));

        Milestone corrected = reload(first);
        assertThat(corrected.sellerWeighing().declaredWeight()).isEqualByComparingTo("9450.25");
        assertThat(corrected.sellerWeighing().evidenceFileId()).isEqualTo(newPhoto);
        assertThat(corrected.sellerWeighing().declarationCount()).isEqualTo(2);
        assertThat(corrected.sellerWeighing().firstDeclaredAt()).as("a correction never extends the window")
                .isEqualTo(firstAt);
        assertThat(declarations.findByMilestoneId(first.milestoneId()))
                .extracting(SellerWeighDeclaration::declarationNumber).containsExactly(1, 2);
        List<JsonNode> events = weighedEvents();
        assertThat(events).extracting(e -> e.at("/payload/declarationNumber").asInt()).containsExactlyInAnyOrder(1, 2);
        for (JsonNode event : events) {
            FrozenSchemas.assertEvent("milestoneSellerWeighed", event);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE seller_weigh_declaration SET seller_declared_weight = 1"
                + " WHERE milestone_id = ?", first.milestoneId())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void afterTheWindow_orOnceTheBuyerRecordedReceipt_theWeightIsClosed() {
        String photo = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9500", photo, null, UUID.randomUUID()));

        jdbc.update("UPDATE milestone SET seller_first_declared_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(61, ChronoUnit.MINUTES)), first.milestoneId());
        assertCode(() -> weigh.execute(weighCommand(first, "9400", photo, null, UUID.randomUUID())),
                ContractConflictException.WEIGH_CORRECTION_CLOSED);

        jdbc.update("UPDATE milestone SET seller_first_declared_at = ?, status = 'BUYER_RECEIVED'"
                + " WHERE milestone_id = ?", Timestamp.from(Instant.now()), first.milestoneId());
        assertCode(() -> weigh.execute(weighCommand(first, "9400", photo, null, UUID.randomUUID())),
                ContractConflictException.WEIGH_CORRECTION_CLOSED);
        assertCode(() -> uploadFile(SELLER, first, UUID.randomUUID()),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(declarations.findByMilestoneId(first.milestoneId())).hasSize(1);
    }

    @Test
    void theSameKeyAgain_isNotASecondDeclaration_andAnotherBodyUnderIt_isRefused() {
        String photo = uploadReady(SELLER, first);
        UUID key = UUID.randomUUID();
        weigh.execute(weighCommand(first, "9500", photo, null, key));

        weigh.execute(weighCommand(first, "9500", photo, null, key));
        assertThatThrownBy(() -> weigh.execute(weighCommand(first, "9400", photo, null, key)))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(declarations.findByMilestoneId(first.milestoneId())).hasSize(1);
        assertThat(weighedEvents()).hasSize(1);
    }

    @Test
    void onlyTheSellerWeighs_andOnlyAFundedMilestone_ofAnActiveContract() {
        String photo = uploadReady(SELLER, first);

        assertThatThrownBy(() -> weigh.execute(weighCommand(first, BUYER, "9500", photo, null, UUID.randomUUID())))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> weigh.execute(new MilestoneEvidenceCommands.Weigh(id(),
                UUID.randomUUID().toString(), SELLER, UUID.randomUUID(), new BigDecimal("1"), photo, null, "1.0",
                "0".repeat(64)))).isInstanceOf(MilestoneNotFoundException.class);
        // The second milestone is not funded yet; its own upload is refused for the same reason.
        assertCode(() -> uploadFile(SELLER, second, UUID.randomUUID()),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertCode(() -> weigh.execute(weighCommand(second, "9500", photo, null, UUID.randomUUID())),
                MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED);

        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE contract_id = ?", id());
        assertCode(() -> weigh.execute(weighCommand(first, "9500", photo, null, UUID.randomUUID())),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.IN_PROGRESS);
    }

    @Test
    void theFiles_mustBeTheSellersOwnUploads_forThisMilestone_differentAndScanned() {
        String processing = uploadFile(SELLER, first, UUID.randomUUID()).fileId();
        String ready = uploadReady(SELLER, first);
        String rejected = uploadFile(SELLER, first, UUID.randomUUID()).fileId();
        scans.onFailed(message(failed(rejected)));

        assertCode(() -> weigh.execute(weighCommand(first, "9500", UUID.randomUUID().toString(), null,
                UUID.randomUUID())), MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED);
        assertCode(() -> weigh.execute(weighCommand(first, "9500", ready, ready, UUID.randomUUID())),
                MilestoneRequestInvalidException.EVIDENCE_FILE_REUSED);
        assertCode(() -> weigh.execute(weighCommand(first, "9500", processing, null, UUID.randomUUID())),
                ContractConflictException.EVIDENCE_NOT_READY);
        assertCode(() -> weigh.execute(weighCommand(first, "9500", ready, rejected, UUID.randomUUID())),
                ContractConflictException.EVIDENCE_REJECTED);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.IN_PROGRESS);
        assertThat(weighedEvents()).isEmpty();
    }

    @Test
    void theConsent_mustBeTheStatementInForce_onExactlyThisDeclaration() {
        String photo = uploadReady(SELLER, first);
        String hashOfAnotherWeight = SellerWeighConsent.contentHash(first.milestoneId(), new BigDecimal("9000"),
                photo, null);

        assertCode(() -> weigh.execute(new MilestoneEvidenceCommands.Weigh(id(), first.milestoneId(), SELLER,
                UUID.randomUUID(), new BigDecimal("9500"), photo, null, "1.0", hashOfAnotherWeight)),
                ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertCode(() -> weigh.execute(new MilestoneEvidenceCommands.Weigh(id(), first.milestoneId(), SELLER,
                UUID.randomUUID(), new BigDecimal("9500"), photo, null, "0.9",
                SellerWeighConsent.contentHash(first.milestoneId(), new BigDecimal("9500"), photo, null))),
                ContractConflictException.CONSENT_STATEMENT_OUTDATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE subject_id = ?", Integer.class,
                first.milestoneId())).isZero();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.IN_PROGRESS);
    }

    @Test
    void anUpload_isRecordedForItsUploader_andAScanReportedEarly_isNotLost() {
        UUID key = UUID.randomUUID();
        String expectedFileId = UUID.nameUUIDFromBytes((SELLER + "|" + key).getBytes(StandardCharsets.UTF_8))
                .toString();
        scans.onReady(message(ready(expectedFileId)));

        MilestoneEvidenceCommands.Uploaded uploaded = uploadFile(SELLER, first, key);

        assertThat(uploaded.fileId()).isEqualTo(expectedFileId);
        assertThat(uploaded.status()).as("file.ready arrived before the upload was recorded")
                .isEqualTo(EvidenceScanStatus.READY);
        assertThat(jdbc.queryForMap("SELECT uploaded_by, uploader_role FROM milestone_evidence_file"
                + " WHERE file_id = ?", expectedFileId))
                .containsEntry("uploaded_by", SELLER).containsEntry("uploader_role", "SELLER");
    }

    @Test
    void anUploadRepeated_answersWithTheSameFile_evenAfterTheTurnPassed_butNotForAnotherMilestone() {
        UUID key = UUID.randomUUID();
        MilestoneEvidenceCommands.Uploaded uploaded = uploadFile(SELLER, first, key);
        int storedBefore = fileStore.stored;
        jdbc.update("UPDATE milestone SET status = 'SETTLED' WHERE milestone_id = ?", first.milestoneId());

        assertThat(uploadFile(SELLER, first, key)).isEqualTo(uploaded);
        assertThat(fileStore.stored).as("a repeat is not sent to file-service again").isEqualTo(storedBefore);
        assertThatThrownBy(() -> uploadFile(SELLER, second, key)).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void theBuyer_uploadsOnlyOnceTheSellerWeighed_andAnOutsiderNever() {
        assertCode(() -> uploadFile(BUYER, first, UUID.randomUUID()),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThatThrownBy(() -> uploadFile(UUID.randomUUID().toString(), first, UUID.randomUUID()))
                .isInstanceOf(UnauthorizedContractAccessException.class);

        String photo = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9500", photo, null, UUID.randomUUID()));

        assertThat(uploadFile(BUYER, first, UUID.randomUUID()).status()).isEqualTo(EvidenceScanStatus.PROCESSING);
        assertThat(uploadFile(SELLER, first, UUID.randomUUID()).status()).as("seller, inside the correction window")
                .isEqualTo(EvidenceScanStatus.PROCESSING);
    }

    @Test
    void aFailedConfirmAttached_isSentAgainLater() {
        fileStore.confirmFails = true;
        String photo = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9500", photo, null, UUID.randomUUID()));
        assertThat(fileStore.confirmed).isEmpty();

        fileStore.confirmFails = false;
        assertThat(attachment.retryPending()).as("backed off: not due yet").isZero();
        assertThat(jdbc.queryForObject("SELECT attach_attempts FROM milestone_evidence_file WHERE file_id = ?",
                Integer.class, photo)).isEqualTo(1);

        jdbc.update("UPDATE milestone_evidence_file SET next_attach_attempt_at = ? WHERE file_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), photo);
        assertThat(attachment.retryPending()).isEqualTo(1);
        assertThat(attachment.retryPending()).isZero();
        assertThat(fileStore.confirmed).containsExactly(photo);
    }

    @Test
    void aFileThatKeepsFailing_doesNotStarveANewerOne() {
        fileStore.confirmFails = true;
        String stuck = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9500", stuck, null, UUID.randomUUID()));
        // The stuck file failed many times and is backed off for hours; a newer named file is due now.
        jdbc.update("UPDATE milestone_evidence_file SET attach_attempts = 20,"
                + " next_attach_attempt_at = ? WHERE file_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().plus(java.time.Duration.ofHours(6))), stuck);
        String fresh = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9501", fresh, null, UUID.randomUUID()));
        jdbc.update("UPDATE milestone_evidence_file SET next_attach_attempt_at = ? WHERE file_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), fresh);

        fileStore.confirmFails = false;
        assertThat(attachment.retryPending()).isEqualTo(1);
        assertThat(fileStore.confirmed).containsExactly(fresh);
    }

    @Test
    void scanEvents_outsideTheSchema_areRefused_andDuplicatesIgnored() {
        Map<String, Object> extra = readyPayload(UUID.randomUUID().toString());
        extra.put("note", "x");
        Map<String, Object> missing = readyPayload(UUID.randomUUID().toString());
        missing.remove("fileSize");
        Map<String, Object> badChannel = readyPayload(UUID.randomUUID().toString());
        badChannel.put("ingestChannel", "FTP");

        assertThatThrownBy(() -> scans.onReady(message(envelope("file.ready", extra))))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> scans.onReady(message(envelope("file.ready", missing))))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> scans.onReady(message(envelope("file.ready", badChannel))))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> scans.onFailed(message(envelope("file.ready",
                readyPayload(UUID.randomUUID().toString()))))).isInstanceOf(InvalidEventPayloadException.class);

        String fileId = UUID.randomUUID().toString();
        EventEnvelope once = ready(fileId);
        scans.onReady(message(once));
        scans.onReady(message(once));
        scans.onFailed(message(failed(fileId)));
        assertThat(jdbc.queryForObject("SELECT status FROM file_scan_outcome WHERE file_id = ?", String.class, fileId))
                .as("the first outcome stays").isEqualTo("READY");
    }

    @Test
    void theDetail_showsTheDeclarationInForce_itsWindow_andEveryFile_toPartiesAndStaffOnly() {
        assertThat(detail.execute(id(), first.milestoneId(), BUYER, java.util.Set.of("BUYER")).sellerDeclaration())
                .isNull();
        String photo = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9500", photo, null, UUID.randomUUID()));
        String newPhoto = uploadReady(SELLER, first);
        weigh.execute(weighCommand(first, "9450.5", newPhoto, null, UUID.randomUUID()));

        MilestoneDetailView view = detail.execute(id(), first.milestoneId(), UUID.randomUUID().toString(),
                java.util.Set.of("ARBITRATOR"));

        Milestone m = reload(first);
        assertThat(view.sellerDeclaration().declaration().declarationNumber()).isEqualTo(2);
        assertThat(view.sellerDeclaration().declaration().declaredWeight()).isEqualByComparingTo("9450.5");
        assertThat(view.sellerDeclaration().correctionWindowClosesAt())
                .isEqualTo(m.sellerWeighing().firstDeclaredAt().plus(java.time.Duration.ofHours(1)));
        assertThat(view.evidence()).extracting(f -> f.fileId()).containsExactlyInAnyOrder(photo, newPhoto);
        assertThat(view.effectiveUnitPrice().wire()).isEqualTo("95000.00");
        assertThat(view.effectiveDeliveryDeadline()).isAfter(m.expectedDeliveryDate());

        assertThatThrownBy(() -> detail.execute(id(), first.milestoneId(), UUID.randomUUID().toString(),
                java.util.Set.of("SELLER"))).isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> detail.execute(id(), first.milestoneId(), UUID.randomUUID().toString(),
                java.util.Set.of("ADMIN"))).as("no fallback for ADMIN")
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> detail.execute(id(), UUID.randomUUID().toString(), BUYER,
                java.util.Set.of("BUYER"))).isInstanceOf(MilestoneNotFoundException.class);
    }

    @Test
    void theEvidenceBytes_areServedOnlyForAReadyFileOfThisMilestone() throws Exception {
        String photo = uploadReady(SELLER, first);
        String scanning = uploadFile(SELLER, first, UUID.randomUUID()).fileId();

        EvidenceFileStore.FileContent content = readEvidence.execute(id(), first.milestoneId(), photo, BUYER,
                java.util.Set.of("BUYER"));
        assertThat(new String(content.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("bytes of " + photo);

        assertCode(() -> readEvidence.execute(id(), first.milestoneId(), scanning, BUYER, java.util.Set.of("BUYER")),
                ContractConflictException.EVIDENCE_NOT_READY);
        assertThatThrownBy(() -> readEvidence.execute(id(), second.milestoneId(), photo, BUYER,
                java.util.Set.of("BUYER"))).isInstanceOf(EvidenceFileNotFoundException.class);
        assertThatThrownBy(() -> readEvidence.execute(id(), first.milestoneId(), photo, UUID.randomUUID().toString(),
                java.util.Set.of("OPERATOR"))).as("OPERATOR does not read evidence bytes")
                .isInstanceOf(UnauthorizedContractAccessException.class);
    }

    // ---- helpers ----

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
        CanonicalTerms terms = CanonicalTerms.of(ContractTermsSnapshot.assemble(input(), goods(), template, rules),
                canonicalizer);
        Contract offered = Contract.offerCoffee(new ContractId(UUID.randomUUID().toString()),
                goods().listingId().toString(), BUYER, SELLER, "HTX Mua", "HTX Bán", "buyer@example.com",
                "seller@example.com", terms);
        contracts.save(offered);
        return offered;
    }
}
