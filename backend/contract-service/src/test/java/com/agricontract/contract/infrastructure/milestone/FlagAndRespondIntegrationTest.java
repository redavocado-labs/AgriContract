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
import com.agricontract.contract.application.usecase.FlagMilestoneUseCase;
import com.agricontract.contract.application.usecase.RespondToMilestoneFlagUseCase;
import com.agricontract.contract.application.usecase.SellerSilenceMilestonesUseCase;
import com.agricontract.contract.application.service.MilestoneContestStep;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.FlagMilestoneConsent;
import com.agricontract.contract.domain.model.milestone.ContestFlagConsent;
import com.agricontract.contract.infrastructure.persistence.breach.JdbcBreachCaseRepository;
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
 * #134 on MySQL (D80, D82): the buyer's flag, the seller's contest and the seller-silence job, through the real
 * repositories, outbox, consent and V19/V20 constraints. The seller weighs and the buyer receives first (#121, #122 code). file-service is a fake.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
        JdbcMilestoneRepository.class, JdbcBreachCaseRepository.class, FlagMilestoneUseCase.class,
        RespondToMilestoneFlagUseCase.class, SellerSilenceMilestonesUseCase.class, MilestoneContestStep.class,
        OutboxContractEventPublisher.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class, MilestoneDeliveryConfiguration.class,
        JdbcMilestoneEvidenceRepository.class, JdbcSellerWeighDeclarationRepository.class,
        JdbcConsentEvidenceRepository.class, JdbcIdempotencyStore.class, UploadMilestoneEvidenceUseCase.class,
        RecordSellerWeighedUseCase.class, RecordBuyerReceiptUseCase.class, ConfirmMilestoneCleanUseCase.class,
        AutoConfirmMilestonesUseCase.class, MilestoneCleanSettlement.class, EvidenceAttachment.class, RecordFileScanResultUseCase.class,
        MilestoneReadAccess.class, GetMilestoneDetailUseCase.class, ReadMilestoneEvidenceUseCase.class,
        FileScanResultConsumer.class, FlagAndRespondIntegrationTest.Fakes.class})
@Testcontainers
class FlagAndRespondIntegrationTest {
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
    @Autowired FlagMilestoneUseCase flag;
    @Autowired RespondToMilestoneFlagUseCase respond;
    @Autowired SellerSilenceMilestonesUseCase silence;
    @Autowired JdbcBreachCaseRepository breachCases;
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
        // The database is shared between tests: only this test's contract may be a candidate of the jobs.
        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE status = 'ACTIVE'");
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
    void theBuyerFlags_theMilestoneAwaitsTheSeller_withOneFlaggedEvent_andNoMoneyEvent() throws Exception {
        receivedByTheBuyer(first, "9000");
        UUID key = UUID.randomUUID();

        flag.execute(flagCommand(first, BUYER, key, FlagIssueType.QUANTITY));

        Milestone flagged = reload(first);
        assertThat(flagged.status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
        assertThat(flagged.flag().issueType()).isEqualTo(FlagIssueType.QUANTITY);
        assertThat(flagged.flag().flaggedAt()).isNotNull();
        assertThat(flagged.flag().contestedAt()).isNull();
        assertThat(flagged.settlement()).isNull();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM milestone WHERE milestone_id = ?",
                first.milestoneId());
        assertThat(row).containsEntry("flag_issue_type", "QUANTITY").containsEntry("status", "AWAITING_SELLER_RESPONSE");
        assertThat(row.get("flagged_at")).isNotNull();
        assertThat(row.get("breach_case_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE subject_id = ? AND action_type ="
                + " 'FLAG_MILESTONE_ISSUE' AND actor_role = 'BUYER' AND statement_code ="
                + " 'FLAG_MILESTONE_ISSUE_AS_BUYER'", Integer.class, first.milestoneId())).isEqualTo(1);

        List<JsonNode> flaggedEvents = events("milestone.flagged");
        assertThat(flaggedEvents).hasSize(1);
        FrozenSchemas.assertEvent("milestoneFlagged", flaggedEvents.get(0));
        JsonNode payload = flaggedEvents.get(0).get("payload");
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("contractId", "milestoneId",
                "flaggedBy", "issueType");
        assertThat(payload.get("contractId").asText()).isEqualTo(id());
        assertThat(payload.get("milestoneId").asText()).isEqualTo(first.milestoneId());
        assertThat(payload.get("flaggedBy").asText()).isEqualTo("BUYER");
        assertThat(payload.get("issueType").asText()).isEqualTo("QUANTITY");
        assertThat(events("milestone.settled")).isEmpty();
        assertThat(events("milestone.dispute_resolved")).isEmpty();
        assertThat(events("breach.reported")).isEmpty();
        assertThat(events("inspection.level1_5_requested")).isEmpty();

        // The same Idempotency-Key again: 204, no new row.
        flag.execute(flagCommand(first, BUYER, key, FlagIssueType.QUANTITY));
        assertThat(events("milestone.flagged")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE subject_id = ? AND action_type ="
                + " 'FLAG_MILESTONE_ISSUE'", Integer.class, first.milestoneId())).isEqualTo(1);
        // A second flag with a new key is refused: the milestone is no longer BUYER_RECEIVED.
        assertCode(() -> flag.execute(flagCommandWithHash(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY,
                "a".repeat(64))), ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(events("milestone.flagged")).hasSize(1);
    }

    @Test
    void aMandatoryInspection_doesNotBlockAFlag() throws Exception {
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
        weigh.execute(weighCommand(m, "9500", uploadReady(SELLER, m), null, UUID.randomUUID()));
        receive.execute(receiveCommand(m, "9400", uploadReady(BUYER, m), null, UUID.randomUUID()));

        flag.execute(flagCommand(m, BUYER, UUID.randomUUID(), FlagIssueType.QUALITY));

        assertThat(reload(m).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
        contract = previous;
    }

    @Test
    void whoMayFlag_andWhichMilestone() {
        receivedByTheBuyer(first, "9000");

        assertThatThrownBy(() -> flag.execute(flagCommand(first, SELLER, UUID.randomUUID(), FlagIssueType.QUANTITY)))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> flag.requireBuyerOfMilestone(id(), first.milestoneId(), SELLER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        Contract other = offered();
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", other.getContractId().value());
        assertThatThrownBy(() -> flag.requireBuyerOfMilestone(other.getContractId().value(), first.milestoneId(),
                BUYER)).isInstanceOf(MilestoneNotFoundException.class);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(events("milestone.flagged")).isEmpty();
    }

    @Test
    void afterTheConfirmWindow_orOnAnInactiveContract_orBeforeTheReceipt_aFlagIsRefused() {
        weighedByTheSeller(first);
        assertCode(() -> flag.execute(flagCommandWithHash(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY,
                "a".repeat(64))), ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);

        receive.execute(receiveCommand(first, "9000", uploadReady(BUYER, first), null, UUID.randomUUID()));
        jdbc.update("UPDATE milestone SET buyer_received_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), first.milestoneId());
        assertCode(() -> flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY)),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);

        jdbc.update("UPDATE milestone SET buyer_received_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now()), first.milestoneId());
        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE contract_id = ?", id());
        assertCode(() -> flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY)),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(events("milestone.flagged")).isEmpty();
    }

    @Test
    void aWrongHashOrStatementOrIssueType_isRefused_withNothingWritten() {
        receivedByTheBuyer(first, "9000");

        assertCode(() -> flag.execute(flagCommandWithHash(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY,
                "b".repeat(64))), ContractConflictException.CONSENT_CONTENT_MISMATCH);
        // The hash binds the issueType: a QUANTITY hash does not carry a QUALITY flag.
        assertCode(() -> flag.execute(flagCommandWithHash(first, BUYER, UUID.randomUUID(), FlagIssueType.QUALITY,
                flagHash(first, FlagIssueType.QUANTITY))), ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertCode(() -> flag.execute(new MilestoneEvidenceCommands.Flag(id(), first.milestoneId(), BUYER,
                        UUID.randomUUID(), FlagIssueType.QUANTITY, "0.9", flagHash(first, FlagIssueType.QUANTITY))),
                ContractConflictException.CONSENT_STATEMENT_OUTDATED);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(reload(first).flag()).isNull();
        assertThat(events("milestone.flagged")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'FLAG_MILESTONE_ISSUE' AND subject_id = ?", Integer.class, first.milestoneId())).isZero();
    }

    @Test
    void theSellerContests_theMilestoneIsContested_aBreachCaseIsOpened_andLevel15IsRequested() throws Exception {
        String[] photos = flaggedMilestone(first, FlagIssueType.QUANTITY);
        UUID key = UUID.randomUUID();

        respond.execute(respondCommand(first, SELLER, key));

        Milestone contested = reload(first);
        assertThat(contested.status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(contested.flag().contestedBy()).isEqualTo(com.agricontract.contract.domain.model.milestone.ContestedBy.SELLER);
        assertThat(contested.flag().contestedAt()).isNotNull();
        assertThat(contested.flag().breachCaseId()).isNotNull();
        assertThat(contested.settlement()).isNull();

        Map<String, Object> breach = jdbc.queryForMap("SELECT * FROM breach_case WHERE milestone_id = ?",
                first.milestoneId());
        assertThat(breach).containsEntry("breach_case_id", contested.flag().breachCaseId())
                .containsEntry("contract_id", id()).containsEntry("requested_by", "BUYER")
                .containsEntry("alleged_breaching_role", "SELLER").containsEntry("breach_reason_code", "SHORTFALL")
                .containsEntry("severity", "MINOR").containsEntry("status", "REPORTED");
        JsonNode evidenceIds = new ObjectMapper().readTree((String) breach.get("evidence_file_ids"));
        assertThat(evidenceIds).hasSize(2);
        assertThat(evidenceIds.get(0).asText()).isEqualTo(photos[0]);
        assertThat(evidenceIds.get(1).asText()).isEqualTo(photos[1]);
        assertThat(breachCases.findByMilestoneId(first.milestoneId()).orElseThrow().evidenceFileIds())
                .containsExactly(photos[0], photos[1]);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE subject_id = ? AND action_type ="
                + " 'CONTEST_MILESTONE_FLAG' AND actor_role = 'SELLER' AND actor_user_id = ?", Integer.class,
                first.milestoneId(), SELLER)).isEqualTo(1);

        List<JsonNode> reported = events("breach.reported");
        assertThat(reported).hasSize(1);
        FrozenSchemas.assertEvent("breachReported", reported.get(0));
        JsonNode reportedPayload = reported.get(0).get("payload");
        assertThat(reportedPayload.fieldNames()).toIterable().containsExactlyInAnyOrder("breachCaseId", "contractId",
                "milestoneId", "requestedBy", "allegedBreachingRole", "breachReasonCode", "severity");
        assertThat(reportedPayload.get("breachCaseId").asText()).isEqualTo(contested.flag().breachCaseId());
        assertThat(reportedPayload.get("milestoneId").asText()).isEqualTo(first.milestoneId());
        assertThat(reportedPayload.get("requestedBy").asText()).isEqualTo("BUYER");
        assertThat(reportedPayload.get("allegedBreachingRole").asText()).isEqualTo("SELLER");
        assertThat(reportedPayload.get("breachReasonCode").asText()).isEqualTo("SHORTFALL");
        assertThat(reportedPayload.get("severity").asText()).isEqualTo("MINOR");

        List<JsonNode> requested = events("inspection.level1_5_requested");
        assertThat(requested).hasSize(1);
        FrozenSchemas.assertEvent("inspectionLevel15Requested", requested.get(0));
        JsonNode requestedPayload = requested.get(0).get("payload");
        assertThat(requestedPayload.fieldNames()).toIterable().containsExactlyInAnyOrder("contractId", "milestoneId",
                "reason", "reportDueBusinessDays", "initiatedByActorId", "requestedAt");
        assertThat(requestedPayload.get("reason").asText()).isEqualTo("DISPUTE_ROUTED");
        assertThat(requestedPayload.get("reportDueBusinessDays").asInt()).isEqualTo(reportDueBusinessDays());
        assertThat(requestedPayload.get("initiatedByActorId").asText()).isEqualTo(SELLER);

        // No money, no remedy and no settlement from a contest.
        assertThat(events("milestone.settled")).isEmpty();
        assertThat(events("milestone.dispute_resolved")).isEmpty();
        assertThat(events("remedy.finalized")).isEmpty();
        assertThat(events("contract.terminated")).isEmpty();

        respond.execute(respondCommand(first, SELLER, key));
        assertThat(events("breach.reported")).hasSize(1);
        assertThat(events("inspection.level1_5_requested")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM breach_case WHERE milestone_id = ?", Integer.class,
                first.milestoneId())).isEqualTo(1);
        assertCode(() -> respond.execute(respondCommandWithHash(first, SELLER, UUID.randomUUID(), "a".repeat(64))),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
    }

    @Test
    void aQualityContest_usesQualityBelowCommitted() {
        flaggedMilestone(first, FlagIssueType.QUALITY);

        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));

        assertThat(jdbc.queryForObject("SELECT breach_reason_code FROM breach_case WHERE milestone_id = ?",
                String.class, first.milestoneId())).isEqualTo("QUALITY_BELOW_COMMITTED");
    }

    @Test
    void whoMayRespond_andWhen() {
        flaggedMilestone(first, FlagIssueType.QUANTITY);

        assertThatThrownBy(() -> respond.execute(respondCommand(first, BUYER, UUID.randomUUID())))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> respond.requireSellerOfMilestone(id(), first.milestoneId(), BUYER))
                .isInstanceOf(UnauthorizedContractAccessException.class);

        jdbc.update("UPDATE milestone SET flagged_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), first.milestoneId());
        assertCode(() -> respond.execute(respondCommand(first, SELLER, UUID.randomUUID())),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
        assertThat(events("breach.reported")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM breach_case WHERE milestone_id = ?", Integer.class,
                first.milestoneId())).isZero();
    }

    @Test
    void respondingToAnUnflaggedMilestone_orWithAWrongHash_isRefused_withNothingWritten() {
        receivedByTheBuyer(first, "9000");
        assertCode(() -> respond.execute(respondCommandWithHash(first, SELLER, UUID.randomUUID(), "a".repeat(64))),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);

        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY));
        assertCode(() -> respond.execute(respondCommandWithHash(first, SELLER, UUID.randomUUID(), "b".repeat(64))),
                ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertCode(() -> respond.execute(new MilestoneEvidenceCommands.Respond(id(), first.milestoneId(), SELLER,
                UUID.randomUUID(), "0.9", contestHash(first))), ContractConflictException.CONSENT_STATEMENT_OUTDATED);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
        assertThat(events("breach.reported")).isEmpty();
        assertThat(events("inspection.level1_5_requested")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'CONTEST_MILESTONE_FLAG' AND subject_id = ?", Integer.class, first.milestoneId())).isZero();
    }

    @Test
    void sellerSilence_onAQuantityFlag_settlesOnTheBuyersWeight_once() throws Exception {
        flaggedMilestone(first, FlagIssueType.QUANTITY);
        expireResponseWindow(first);

        assertThat(silence.execute()).isEqualTo(1);

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().path()).isEqualTo(SettlementPath.SELLER_SILENT_QUANTITY);
        assertThat(settled.settlement().billableQuantityKg()).isEqualByComparingTo("9000");
        // 9000 kg x 95,000 = 855,000,000 of the 902,500,000 locked.
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("855000000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("47500000.00");
        assertThat(jdbc.queryForMap("SELECT * FROM milestone WHERE milestone_id = ?", first.milestoneId()))
                .containsEntry("settlement_path", "SELLER_SILENT_QUANTITY");

        List<JsonNode> settledEvents = events("milestone.settled");
        assertThat(settledEvents).hasSize(1);
        FrozenSchemas.assertEvent("milestoneSettled", settledEvents.get(0));
        assertThat(settledEvents.get(0).at("/payload/lockedAmount").asText()).isEqualTo("902500000.00");
        assertThat(settledEvents.get(0).at("/payload/actualAmount").asText()).isEqualTo("855000000.00");
        List<JsonNode> resolved = events("milestone.dispute_resolved");
        assertThat(resolved).hasSize(1);
        FrozenSchemas.assertEvent("milestoneDisputeResolved", resolved.get(0));
        JsonNode payload = resolved.get(0).get("payload");
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("contractId", "milestoneId",
                "flaggedBy", "flaggedByUserId", "resolutionFavors");
        assertThat(payload.get("flaggedBy").asText()).isEqualTo("BUYER");
        assertThat(payload.get("flaggedByUserId").asText()).isEqualTo(BUYER);
        assertThat(payload.get("resolutionFavors").asText()).isEqualTo("BUYER");
        assertThat(events("milestone.buyer_confirmed")).isEmpty();
        assertThat(events("breach.reported")).isEmpty();
        assertThat(events("inspection.level1_5_requested")).isEmpty();

        assertThat(silence.execute()).isZero();
        assertThat(events("milestone.settled")).hasSize(1);
        assertThat(events("milestone.dispute_resolved")).hasSize(1);
    }

    @Test
    void sellerSilence_onAQualityFlag_isContestedBySystem_withNoActorAndNoConsent() throws Exception {
        flaggedMilestone(first, FlagIssueType.QUALITY);
        expireResponseWindow(first);

        assertThat(silence.execute()).isEqualTo(1);

        Milestone contested = reload(first);
        assertThat(contested.status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(contested.flag().contestedBy()).isEqualTo(com.agricontract.contract.domain.model.milestone.ContestedBy.SYSTEM);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM breach_case WHERE milestone_id = ?", Integer.class,
                first.milestoneId())).isEqualTo(1);
        List<JsonNode> requested = events("inspection.level1_5_requested");
        assertThat(requested).hasSize(1);
        FrozenSchemas.assertEvent("inspectionLevel15Requested", requested.get(0));
        assertThat(requested.get(0).at("/payload/initiatedByActorId").isNull()).isTrue();
        assertThat(requested.get(0).at("/payload/reason").asText()).isEqualTo("DISPUTE_ROUTED");
        assertThat(events("breach.reported")).hasSize(1);
        FrozenSchemas.assertEvent("breachReported", events("breach.reported").get(0));
        assertThat(events("milestone.settled")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_evidence WHERE action_type ="
                + " 'CONTEST_MILESTONE_FLAG' AND subject_id = ?", Integer.class, first.milestoneId())).isZero();

        assertThat(silence.execute()).isZero();
        assertThat(events("breach.reported")).hasSize(1);
        assertThat(events("inspection.level1_5_requested")).hasSize(1);
    }

    @Test
    void sellerSilence_leavesAMilestoneInsideItsWindow_andAContestedOneAlone() {
        flaggedMilestone(first, FlagIssueType.QUANTITY);

        assertThat(silence.execute()).isZero();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);

        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        expireResponseWindow(first);
        assertThat(silence.execute()).isZero();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(events("milestone.settled")).isEmpty();
    }

    @Test
    void aFlaggedMilestone_isIgnoredByTheAutoConfirmJob_evenPastTheConfirmWindow() {
        flaggedMilestone(first, FlagIssueType.QUANTITY);
        jdbc.update("UPDATE milestone SET buyer_received_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), first.milestoneId());

        assertThat(autoConfirm.execute()).isZero();

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
        assertThat(reload(first).settlement()).isNull();
        assertThat(events("milestone.settled")).isEmpty();
        assertThat(events("milestone.buyer_confirmed")).isEmpty();
    }

    @Test
    void theDetail_showsTheFlagAndTheBreachCase_onlyOnceTheyExist() throws Exception {
        receivedByTheBuyer(first, "9000");
        MilestoneDetailResponse before = detailFor(SELLER, "SELLER");
        assertThat(before.flag()).isNull();
        assertThat(before.breachCaseId()).isNull();

        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUALITY));
        MilestoneDetailResponse flagged = detailFor(SELLER, "SELLER");
        assertThat(flagged.milestone().status()).isEqualTo("AWAITING_SELLER_RESPONSE");
        assertThat(flagged.flag().issueType()).isEqualTo("QUALITY");
        assertThat(flagged.flag().flaggedAt()).isNotNull();
        Instant flaggedAt = reload(first).flag().flaggedAt();
        assertThat(flagged.flag().sellerResponseWindowClosesAt())
                .isEqualTo(calendar.deadline(flaggedAt, 2).toString());
        assertThat(flagged.flag().contestedAt()).isNull();
        assertThat(flagged.flag().contestedBy()).isNull();
        assertThat(flagged.breachCaseId()).isNull();

        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        MilestoneDetailResponse contested = detailFor(BUYER, "BUYER");
        assertThat(contested.flag().contestedAt()).isNotNull();
        assertThat(contested.flag().contestedBy()).isEqualTo("SELLER");
        assertThat(contested.breachCaseId()).isEqualTo(reload(first).flag().breachCaseId());

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(contested));
        assertThat(json.get("flag").fieldNames()).toIterable().containsExactlyInAnyOrder("issueType", "flaggedAt",
                "sellerResponseWindowClosesAt", "contestedAt", "contestedBy");
        assertThat(json.has("breachCaseId")).isTrue();
    }

    private MilestoneDetailResponse detailFor(String actor, String role) {
        return MilestoneDetailResponse.from(detail.execute(id(), first.milestoneId(), actor, java.util.Set.of(role)));
    }

    /** Received by the buyer (9500 declared, 9000 received) and flagged. Returns {sellerPhoto, buyerPhoto}. */
    private String[] flaggedMilestone(Milestone milestone, FlagIssueType type) {
        String sellerPhoto = weighedByTheSeller(milestone);
        String buyerPhoto = uploadReady(BUYER, milestone);
        receive.execute(receiveCommand(milestone, "9000", buyerPhoto, null, UUID.randomUUID()));
        flag.execute(flagCommand(milestone, BUYER, UUID.randomUUID(), type));
        return new String[] {sellerPhoto, buyerPhoto};
    }

    private void expireResponseWindow(Milestone milestone) {
        jdbc.update("UPDATE milestone SET flagged_at = ? WHERE milestone_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), milestone.milestoneId());
    }

    private int reportDueBusinessDays() {
        return contracts.findById(contract.getContractId()).orElseThrow().getCoffeeTerms().snapshot().negotiated()
                .inspectionTerms().reportDueBusinessDays();
    }

    private MilestoneEvidenceCommands.Flag flagCommand(Milestone milestone, String actor, UUID key,
                                                       FlagIssueType type) {
        return flagCommandWithHash(milestone, actor, key, type, flagHash(milestone, type));
    }

    private MilestoneEvidenceCommands.Flag flagCommandWithHash(Milestone milestone, String actor, UUID key,
                                                               FlagIssueType type, String hash) {
        return new MilestoneEvidenceCommands.Flag(milestone.contractId(), milestone.milestoneId(), actor, key, type,
                "1.0", hash);
    }

    private String flagHash(Milestone milestone, FlagIssueType type) {
        Milestone current = reload(milestone);
        return FlagMilestoneConsent.contentHash(current.milestoneId(), current.sellerWeighing().declarationCount(),
                current.sellerWeighing().declaredWeight(), current.buyerReceipt().receivedWeight(), type);
    }

    private MilestoneEvidenceCommands.Respond respondCommand(Milestone milestone, String actor, UUID key) {
        return respondCommandWithHash(milestone, actor, key, contestHash(milestone));
    }

    private MilestoneEvidenceCommands.Respond respondCommandWithHash(Milestone milestone, String actor, UUID key,
                                                                     String hash) {
        return new MilestoneEvidenceCommands.Respond(milestone.contractId(), milestone.milestoneId(), actor, key,
                "1.0", hash);
    }

    /** The server's hash of the flag now on the milestone (the milestone must be flagged). */
    private String contestHash(Milestone milestone) {
        Milestone current = reload(milestone);
        if (current.flag() == null) {
            return "c".repeat(64);
        }
        return ContestFlagConsent.contentHash(current);
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

    /** The events of a type written for this test's contract (the database is shared between tests). */
    private List<JsonNode> events(String type) {
        return jdbc.queryForList("SELECT envelope FROM event_outbox WHERE event_type = ?", String.class, type)
                .stream().map(json -> {
                    try {
                        return new ObjectMapper().readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).filter(e -> id().equals(e.at("/payload/contractId").asText())).toList();
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
