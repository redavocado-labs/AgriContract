package com.agricontract.contract.infrastructure.milestone;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.application.service.MilestoneFunding;
import com.agricontract.contract.application.service.MilestoneReadAccess;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.application.usecase.AutoConfirmMilestonesUseCase;
import com.agricontract.contract.application.usecase.ConfirmMilestoneCleanUseCase;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.usecase.GetBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ListBreachCasesForArbitrationUseCase;
import com.agricontract.contract.application.usecase.ResolveBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ReviewBreachCaseUseCase;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.infrastructure.web.dto.BreachCaseResponse;
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
import java.util.Set;
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
 * #135 on MySQL (D80, D81): review, resolve and the reads of a BreachCase through the real repositories and V21. The seller weighs and the buyer receives first (#121, #122 code). file-service is a fake.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({com.agricontract.contract.application.service.ContractCompletion.class, com.agricontract.contract.application.service.Level15Inspections.class, com.agricontract.contract.application.service.DisputeExitStep.class, com.agricontract.contract.application.service.MilestoneContestStep.class, com.agricontract.contract.infrastructure.persistence.inspection.JdbcLevel15RequestRepository.class, com.agricontract.contract.infrastructure.persistence.inspection.JdbcLevel15AssignmentRepository.class, com.agricontract.contract.infrastructure.persistence.dispute.JdbcSettlementProposalRepository.class, com.agricontract.contract.infrastructure.persistence.breach.JdbcBreachCaseRepository.class, com.agricontract.contract.infrastructure.persistence.remedy.JdbcRemedyDecisionRepository.class, com.agricontract.contract.infrastructure.persistence.inspection.JdbcInspectionReportRepository.class,
        BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
        JdbcMilestoneRepository.class, JdbcInspectionReportRepository.class, JdbcBreachCaseRepository.class, ReviewBreachCaseUseCase.class, ResolveBreachCaseUseCase.class, com.agricontract.contract.infrastructure.persistence.remedy.JdbcRemedyDecisionRepository.class, com.agricontract.contract.application.service.RemedySettlement.class,
        GetBreachCaseUseCase.class, ListBreachCasesForArbitrationUseCase.class, FlagMilestoneUseCase.class,
        RespondToMilestoneFlagUseCase.class, SellerSilenceMilestonesUseCase.class, MilestoneContestStep.class,
        OutboxContractEventPublisher.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class, MilestoneDeliveryConfiguration.class,
        JdbcMilestoneEvidenceRepository.class, JdbcSellerWeighDeclarationRepository.class,
        JdbcConsentEvidenceRepository.class, JdbcIdempotencyStore.class, UploadMilestoneEvidenceUseCase.class,
        RecordSellerWeighedUseCase.class, RecordBuyerReceiptUseCase.class, ConfirmMilestoneCleanUseCase.class,
        AutoConfirmMilestonesUseCase.class, MilestoneCleanSettlement.class, EvidenceAttachment.class, RecordFileScanResultUseCase.class,
        MilestoneReadAccess.class, GetMilestoneDetailUseCase.class, ReadMilestoneEvidenceUseCase.class,
        FileScanResultConsumer.class, BreachCaseReviewIntegrationTest.Fakes.class})
@Testcontainers
class BreachCaseReviewIntegrationTest {
    private static final String BUYER = "0b7e3b7a-1c1e-4c5e-9f0a-3d2b1a0c9e8f";
    private static final String ARBITRATOR = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
    private static final String STRANGER = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a";
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
    @Autowired ReviewBreachCaseUseCase reviewCase;
    @Autowired ResolveBreachCaseUseCase resolveCase;
    @Autowired GetBreachCaseUseCase getCase;
    @Autowired ListBreachCasesForArbitrationUseCase listCases;
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
        jdbc.update("DELETE FROM remedy_leg");
        jdbc.update("DELETE FROM remedy_decision");
        jdbc.update("DELETE FROM breach_case");
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
    void aContestedCase_cannotBeResolvedWithoutAComputedResolution_andNothingIsWritten() {
        String caseId = contestedCase();
        reviewCase.execute(review(caseId, UUID.randomUUID()));
        int outbox = outboxCount();

        assertCode(() -> resolveCase.execute(resolve(caseId, null, DecisionSource.ARBITRATOR)),
                ContractConflictException.INSPECTION_REPORT_PENDING);
        assertCode(() -> resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5)),
                ContractConflictException.INSPECTION_REPORT_PENDING);

        BreachCase unchanged = breachCases.findById(caseId).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(BreachCaseStatus.UNDER_REVIEW);
        assertThat(unchanged.finalBreachingRole()).isNull();
        assertThat(unchanged.decisionSource()).isNull();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(outboxCount()).isEqualTo(outbox);
    }

    @Test
    void aComputedResolution_isApprovedWhenTheDecisionEqualsIt_andRefusedOtherwise_withNothingWritten() {
        String caseId = contestedCase();
        reviewCase.execute(review(caseId, UUID.randomUUID()));
        seedComputed(caseId, "SELLER", "NON_CONFORMING");
        int outbox = outboxCount();

        assertCode(() -> resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5)),
                ContractConflictException.RESOLUTION_MISMATCH);
        assertCode(() -> resolveCase.execute(resolve(caseId, SignerRole.BUYER, DecisionSource.INSPECTOR_L1_5)),
                ContractConflictException.RESOLUTION_MISMATCH);
        assertCode(() -> resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.ARBITRATOR)),
                ContractConflictException.RESOLUTION_MISMATCH);
        assertThat(breachCases.findById(caseId).orElseThrow().status()).isEqualTo(BreachCaseStatus.UNDER_REVIEW);
        assertThat(breachCases.findById(caseId).orElseThrow().finalBreachingRole()).isNull();

        UUID key = UUID.randomUUID();
        resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5, key));

        BreachCase resolved = breachCases.findById(caseId).orElseThrow();
        assertThat(resolved.status()).isEqualTo(BreachCaseStatus.RESOLVED);
        assertThat(resolved.finalBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(resolved.decisionSource()).isEqualTo(DecisionSource.INSPECTOR_L1_5);
        assertThat(resolved.decisionEvidenceRefs()).containsExactly("report-1");
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.resolvedByActorId()).isEqualTo(ARBITRATOR);
        assertThat(resolved.contestStatus().name()).isEqualTo("OPEN");
        assertThat(resolved.contestDeadline()).isAfter(resolved.resolvedAt());
        // The allegation is untouched and the milestone does not move (settlement after resolution is #137).
        assertThat(resolved.requestedBy().name()).isEqualTo("BUYER");
        assertThat(resolved.allegedBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(outboxCount()).isEqualTo(outbox);

        // Replay: no change; a second resolve with a new key is refused.
        resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5, key));
        assertThat(breachCases.findById(caseId).orElseThrow().resolvedAt()).isEqualTo(resolved.resolvedAt());
        assertCode(() -> resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5)),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
    }

    @Test
    void aNoFaultComputedResolution_isApprovedWithANullRole() {
        String caseId = contestedCase();
        reviewCase.execute(review(caseId, UUID.randomUUID()));
        seedComputed(caseId, null, "CONFORMING");

        resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5));

        BreachCase resolved = breachCases.findById(caseId).orElseThrow();
        assertThat(resolved.status()).isEqualTo(BreachCaseStatus.RESOLVED);
        assertThat(resolved.finalBreachingRole()).isNull();
        assertThat(resolved.computedResolution().qualityDisposition().name()).isEqualTo("CONFORMING");
    }

    @Test
    void aContractLevelCase_isReviewedAndResolvedByTheArbitrator_withNoFault_andNoOutboxRow() {
        String caseId = contractLevelCase("2036-01-01 00:00:00.000000", "REPORTED", false);
        int outbox = outboxCount();
        UUID reviewKey = UUID.randomUUID();

        reviewCase.execute(review(caseId, reviewKey));
        assertThat(breachCases.findById(caseId).orElseThrow().status()).isEqualTo(BreachCaseStatus.UNDER_REVIEW);
        reviewCase.execute(review(caseId, reviewKey));
        assertCode(() -> reviewCase.execute(review(caseId, UUID.randomUUID())),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);

        UUID key = UUID.randomUUID();
        resolveCase.execute(resolve(caseId, null, DecisionSource.ARBITRATOR, key));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM breach_case WHERE breach_case_id = ?", caseId);
        assertThat(row).containsEntry("status", "RESOLVED").containsEntry("decision_source", "ARBITRATOR")
                .containsEntry("resolved_by_actor_id", ARBITRATOR).containsEntry("contest_status", "NOT_APPLICABLE")
                .containsEntry("alleged_breaching_role", "SELLER");
        assertThat(row.get("final_breaching_role")).isNull();
        assertThat(row.get("resolved_at")).isNotNull();
        assertThat(row.get("contest_deadline")).isNull();
        assertThat((String) row.get("decision_evidence_refs")).contains("report-1");
        resolveCase.execute(resolve(caseId, null, DecisionSource.ARBITRATOR, key));
        assertThat(outboxCount()).isEqualTo(outbox);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.IN_PROGRESS);
    }

    @Test
    void theFinalRole_isNeverDerivedFromTheAllegation() {
        String caseId = contractLevelCase("2036-01-01 00:00:00.000000", "UNDER_REVIEW", false);

        resolveCase.execute(resolve(caseId, SignerRole.BUYER, DecisionSource.ARBITRATOR));

        BreachCase resolved = breachCases.findById(caseId).orElseThrow();
        assertThat(resolved.allegedBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(resolved.finalBreachingRole()).isEqualTo(SignerRole.BUYER);
    }

    @Test
    void aResolveFromReported_orOnAnUnknownCase_isRefused() {
        String caseId = contractLevelCase("2036-01-01 00:00:00.000000", "REPORTED", false);

        assertCode(() -> resolveCase.execute(resolve(caseId, null, DecisionSource.ARBITRATOR)),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        assertThat(breachCases.findById(caseId).orElseThrow().status()).isEqualTo(BreachCaseStatus.REPORTED);
        assertThatThrownBy(() -> resolveCase.requireCaseOfContract(id(), UUID.randomUUID().toString()))
                .isInstanceOf(BreachCaseNotFoundException.class);
        Contract other = offered();
        assertThatThrownBy(() -> resolveCase.requireCaseOfContract(other.getContractId().value(), caseId))
                .isInstanceOf(BreachCaseNotFoundException.class);
        assertThatThrownBy(() -> reviewCase.execute(new BreachCaseCommands.Review(other.getContractId().value(),
                caseId, ARBITRATOR, UUID.randomUUID()))).isInstanceOf(BreachCaseNotFoundException.class);
    }

    @Test
    void getBreachCase_isForPartiesAndTheArbitrator_withEveryKey() throws Exception {
        String caseId = contestedCase();
        seedComputed(caseId, "SELLER", "NON_CONFORMING");
        Set<String> required = Set.of("breachCaseId", "contractId", "milestoneId", "requestedBy",
                "allegedBreachingRole", "breachReasonCode", "severity", "evidenceFileIds", "finalBreachingRole",
                "decisionSource", "status", "contestStatus", "contestDeadline", "computedResolution", "reportedAt",
                "resolvedAt");

        for (String[] reader : new String[][] {{BUYER, "BUYER"}, {SELLER, "SELLER"}, {ARBITRATOR, "ARBITRATOR"}}) {
            JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
                    BreachCaseResponse.from(getCase.execute(id(), caseId, reader[0], Set.of(reader[1])))));
            assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(required);
            assertThat(json.get("status").asText()).isEqualTo("REPORTED");
            assertThat(json.get("contestStatus").asText()).isEqualTo("NOT_APPLICABLE");
            assertThat(json.get("contestDeadline").isNull()).isTrue();
            assertThat(json.get("resolvedAt").isNull()).isTrue();
            assertThat(json.get("finalBreachingRole").isNull()).isTrue();
            assertThat(json.get("decisionSource").isNull()).isTrue();
            assertThat(json.get("severity").asText()).isEqualTo("MINOR");
            assertThat(json.get("evidenceFileIds")).hasSize(2);
            JsonNode computed = json.get("computedResolution");
            assertThat(computed.fieldNames()).toIterable().containsExactlyInAnyOrder("inspectionReportId", "issueType",
                    "acceptedQuantityKg", "qualityDisposition", "finalBreachingRole", "decisionSource",
                    "sellerEntitlementAmount", "buyerRefundAmount", "prevailingRole");
            assertThat(computed.get("decisionSource").asText()).isEqualTo("INSPECTOR_L1_5");
            assertThat(computed.get("sellerEntitlementAmount").asText()).isEqualTo("855000000.00");
            assertThat(computed.get("buyerRefundAmount").asText()).isEqualTo("47500000.00");
            assertThat(computed.get("acceptedQuantityKg").decimalValue()).isEqualByComparingTo("9000");
        }
        assertThatThrownBy(() -> getCase.execute(id(), caseId, STRANGER, Set.of("BUYER")))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> getCase.execute(id(), caseId, OPERATOR_ID, Set.of("OPERATOR")))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        Contract other = offered();
        assertThatThrownBy(() -> getCase.execute(other.getContractId().value(), caseId, ARBITRATOR,
                Set.of("ARBITRATOR"))).isInstanceOf(BreachCaseNotFoundException.class);
        assertThatThrownBy(() -> getCase.execute(other.getContractId().value(), caseId, BUYER, Set.of("BUYER")))
                .isInstanceOf(BreachCaseNotFoundException.class);
        assertThatThrownBy(() -> getCase.execute(other.getContractId().value(), caseId, STRANGER, Set.of("BUYER")))
                .isInstanceOf(UnauthorizedContractAccessException.class);
    }

    @Test
    void theQueue_filtersByStatusAndAwaitingApproval_pagesAndOrdersNewestFirst() throws Exception {
        String oldest = contractLevelCase("2036-01-01 00:00:00.000000", "REPORTED", false);
        String middle = contractLevelCase("2036-01-02 00:00:00.000000", "UNDER_REVIEW", true);
        String newest = contractLevelCase("2036-01-03 00:00:00.000000", "REPORTED", true);
        String resolvedWithComputed = contractLevelCase("2036-01-04 00:00:00.000000", "RESOLVED", true);

        BreachCaseCommands.BreachCasePageView all = listCases.execute(null, false, 0, 20);
        assertThat(all.content()).extracting(BreachCase::breachCaseId)
                .containsExactly(resolvedWithComputed, newest, middle, oldest);
        assertThat(all.totalElements()).isEqualTo(4);
        assertThat(all.totalPages()).isEqualTo(1);

        assertThat(listCases.execute(BreachCaseStatus.REPORTED, false, 0, 20).content())
                .extracting(BreachCase::breachCaseId).containsExactly(newest, oldest);
        assertThat(listCases.execute(null, true, 0, 20).content()).extracting(BreachCase::breachCaseId)
                .containsExactly(newest, middle);
        assertThat(listCases.execute(BreachCaseStatus.REPORTED, true, 0, 20).content())
                .extracting(BreachCase::breachCaseId).containsExactly(newest);

        BreachCaseCommands.BreachCasePageView second = listCases.execute(null, false, 1, 3);
        assertThat(second.content()).extracting(BreachCase::breachCaseId).containsExactly(oldest);
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.totalPages()).isEqualTo(2);

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
                BreachCaseResponse.Page.from(all)));
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("content", "page", "size",
                "totalElements", "totalPages");

        assertThatThrownBy(() -> listCases.execute(null, false, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listCases.execute(null, false, 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listCases.execute(null, false, -1, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final String OPERATOR_ID = "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d";

    /** A contested milestone (QUANTITY, 9500 declared, 9000 received) through the #134 flow; returns the case id. */
    private String contestedCase() {
        flaggedMilestone(first, FlagIssueType.QUANTITY);
        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        return reload(first).flag().breachCaseId();
    }

    /** A case with no milestone (contract level), inserted directly: allegation against the seller. */
    private String contractLevelCase(String reportedAt, String status, boolean withComputed) {
        String caseId = UUID.randomUUID().toString();
        boolean resolved = status.equals("RESOLVED");
        jdbc.update("INSERT INTO breach_case (breach_case_id, contract_id, milestone_id, requested_by,"
                + " alleged_breaching_role, breach_reason_code, severity, status, evidence_file_ids, reported_at,"
                + " decision_source, resolved_at) VALUES (?, ?, NULL, 'BUYER', 'SELLER', 'NON_DELIVERY', 'MATERIAL',"
                + " ?, '[]', ?, ?, ?)", caseId, id(), status, reportedAt, resolved ? "ARBITRATOR" : null,
                resolved ? reportedAt : null);
        if (withComputed) {
            seedComputed(caseId, null, "CONFORMING");
        }
        return caseId;
    }

    /** What #246 will write: the computed resolution of a confirmed Level 1.5 report (9000 of 9500 kg at 95,000). */
    private void seedComputed(String caseId, String finalRole, String disposition) {
        String json = "{\"inspectionReportId\":\"" + UUID.randomUUID() + "\",\"issueType\":\"QUANTITY\","
                + "\"acceptedQuantityKg\":\"9000\",\"qualityDisposition\":\"" + disposition + "\","
                + "\"finalBreachingRole\":" + (finalRole == null ? "null" : "\"" + finalRole + "\"") + ","
                + "\"decisionSource\":\"INSPECTOR_L1_5\",\"sellerEntitlementAmount\":\"855000000.00\","
                + "\"buyerRefundAmount\":\"47500000.00\",\"prevailingRole\":\"BUYER\"}";
        jdbc.update("UPDATE breach_case SET computed_resolution = ? WHERE breach_case_id = ?", json, caseId);
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class);
    }

    private BreachCaseCommands.Review review(String caseId, UUID key) {
        return new BreachCaseCommands.Review(id(), caseId, ARBITRATOR, key);
    }

    private BreachCaseCommands.Resolve resolve(String caseId, SignerRole role, DecisionSource source) {
        return resolve(caseId, role, source, UUID.randomUUID());
    }

    private BreachCaseCommands.Resolve resolve(String caseId, SignerRole role, DecisionSource source, UUID key) {
        return new BreachCaseCommands.Resolve(id(), caseId, ARBITRATOR, key, role, source, List.of("report-1"));
    }

    /** Received by the buyer (9500 declared, 9000 received) and flagged. Returns {sellerPhoto, buyerPhoto}. */
    private String[] flaggedMilestone(Milestone milestone, FlagIssueType type) {
        String sellerPhoto = weighedByTheSeller(milestone);
        String buyerPhoto = uploadReady(BUYER, milestone);
        receive.execute(receiveCommand(milestone, "9000", buyerPhoto, null, UUID.randomUUID()));
        flag.execute(flagCommand(milestone, BUYER, UUID.randomUUID(), type));
        return new String[] {sellerPhoto, buyerPhoto};
    }

    private MilestoneEvidenceCommands.Flag flagCommand(Milestone milestone, String actor, UUID key,
                                                       FlagIssueType type) {
        return flagCommandWithHash(milestone, actor, key, type, flagHash(milestone, type));
    }

    private MilestoneEvidenceCommands.Flag flagCommandWithHash(Milestone milestone, String actor, UUID key,
                                                               FlagIssueType type, String hash) {
        return new MilestoneEvidenceCommands.Flag(milestone.contractId(), milestone.milestoneId(), actor, key, type,
                "1.1", hash);
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
