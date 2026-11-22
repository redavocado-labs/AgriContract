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
import com.agricontract.contract.application.usecase.ApplyInspectionReportUseCase;
import com.agricontract.contract.infrastructure.messaging.InspectionReportConfirmedConsumer;
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
 * #246 on MySQL (D77, D81, D83, D85, D89, D90): inspection.report_confirmed through the real repositories, outbox, V22 and the consumer. The seller weighs and the buyer receives first. file-service is a fake.
 */
@DataJpaTest(properties = "agricontract.eventing.outbox.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
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
        FileScanResultConsumer.class, ApplyInspectionReportUseCase.class, InspectionReportConfirmedConsumer.class, InspectionReportIntegrationTest.Fakes.class})
@Testcontainers
class InspectionReportIntegrationTest {
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


    @Autowired InspectionReportConfirmedConsumer reportConsumer;

    private static final String INSPECTOR = "6e5d4c3b-2a19-4f8e-9d7c-0b1a2c3d4e5f";
    private static final String CONFORMING = "12.9/0.5/4.0";
    private static final String PARTIALLY = "13.5/0.8/7.5";
    private static final String NON_CONFORMING = "15.0/0.5/5.0";

    /**
     * An ACTIVE contract whose first milestone is funded at 15,000 per kg with a locked batch of 150,000,000
     * (IN_PROGRESS); the second is still CREATED. The quality policy is the one of the #136 examples.
     */
    private void activate(boolean mandatory) {
        jdbc.update("DELETE FROM remedy_leg");
        jdbc.update("DELETE FROM remedy_decision");
        jdbc.update("DELETE FROM breach_case");
        fileStore.confirmed.clear();
        fileStore.confirmFails = false;
        contract = offered(terms(mandatory));
        // The database is shared between tests: only this test's contract may be a candidate of the jobs.
        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE status = 'ACTIVE'");
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", id());
        Contract active = contracts.findById(contract.getContractId()).orElseThrow();
        List<Milestone> planned = transactions.inTransaction(
                () -> funding.planOnActivation(active, Instant.now(), id(), null));
        first = planned.get(0);
        second = planned.get(1);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("UPDATE milestone SET funding_status = 'LOCKED', effective_unit_price = 15000.00,"
                + " batch_amount = 150000000, funding_requested_at = ?, funding_locked_at = ?, status = 'IN_PROGRESS'"
                + " WHERE milestone_id = ?", now, now, first.milestoneId());
    }

    private static com.agricontract.contract.domain.model.terms.ContractTermsInput terms(boolean mandatory) {
        var t = mandatory ? inputMandatoryInspection() : input();
        var moisture = max("0.5", "1.0", "2.0", "0.02");
        var foreign = max("0.2", "0.5", "1.0", "0.03");
        var black = max("1.0", "2.0", "4.0", "0.01");
        var quality = new com.agricontract.contract.domain.model.terms.CoffeeQualityTerms("COFFEE",
                new com.agricontract.contract.domain.model.terms.CoffeeQualitySpec("COFFEE",
                        com.agricontract.contract.domain.model.terms.CoffeeType.ROBUSTA, new BigDecimal("12.5"),
                        new BigDecimal("0.5"), new BigDecimal("5.0")),
                new com.agricontract.contract.domain.model.terms.CoffeeQualityDeviationPolicy(
                        new com.agricontract.contract.domain.model.terms.ExactMatchRejectPolicy(true), moisture,
                        foreign, black));
        return new com.agricontract.contract.domain.model.terms.ContractTermsInput(t.agreedPrice(), t.currency(),
                t.milestoneSchedule(), quality, t.deliveryTerms(), t.inspectionTerms(), t.priceBand(),
                t.priceAdjustmentRule(), t.toleranceRate(), t.shortfallPenaltyThreshold(), t.buyerPenaltyRate(),
                t.sellerPenaltyRate(), t.forceMajeureReportWindowDays(), t.buyerConfirmWindowDays(),
                t.buyerDepositRate(), t.disputeFloorReleaseRate(), t.sellerDepositRate(), t.level2InspectorOrg(),
                t.legalProfile(), t.fundingLeadBusinessDays());
    }

    private static com.agricontract.contract.domain.model.terms.NumericDeviationPolicy max(String tol, String pen,
                                                                                         String rej, String rate) {
        return new com.agricontract.contract.domain.model.terms.NumericDeviationPolicy(
                com.agricontract.contract.domain.model.terms.DeviationDirection.MAXIMUM, new BigDecimal(tol),
                new BigDecimal(pen), new BigDecimal(rej), new BigDecimal(rate));
    }

    /** The seller weighs sellerKg and the buyer receives buyerKg. */
    private void delivered(Milestone milestone, String sellerKg, String buyerKg) {
        String sellerPhoto = uploadReady(SELLER, milestone);
        weigh.execute(weighCommand(milestone, sellerKg, sellerPhoto, null, UUID.randomUUID()));
        String buyerPhoto = uploadReady(BUYER, milestone);
        receive.execute(receiveCommand(milestone, buyerKg, buyerPhoto, null, UUID.randomUUID()));
    }

    // ---- the event ----

    private static Map<String, Object> normalizedResult(String status, String accepted, String measured,
                                                        String metrics) {
        String[] m = metrics.split("/");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("commodity", "COFFEE");
        actual.put("type", "ROBUSTA");
        actual.put("moisturePercent", Double.parseDouble(m[0]));
        actual.put("foreignMatterPercent", Double.parseDouble(m[1]));
        actual.put("blackBrokenBeansPercent", Double.parseDouble(m[2]));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultSchemaVersion", "1.0");
        result.put("measuredQuantityKg", Double.parseDouble(measured));
        result.put("acceptedQuantityKg", Double.parseDouble(accepted));
        result.put("measurementStatus", status);
        result.put("actualQualityMetrics", actual);
        if (status.equals("INCONCLUSIVE")) {
            result.put("inconclusiveReasons", List.of("sample unreadable"));
        }
        result.put("determinedAt", "2026-11-20T02:00:00Z");
        return result;
    }

    private static String sha256(Map<String, Object> value) {
        try {
            byte[] canonical = new org.erdtman.jcs.JsonCanonicalizer(new ObjectMapper().writeValueAsString(value))
                    .getEncodedUTF8();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A well-formed, correctly hashed LEVEL_1_5 event for the milestone. */
    private Map<String, Object> reportPayload(String milestoneId, String status, String accepted, String measured,
                                              String metrics) {
        String reportId = UUID.randomUUID().toString();
        String confirmedAt = "2026-11-20T03:00:00Z";
        String fileHash = "a".repeat(64);
        Map<String, Object> result = normalizedResult(status, accepted, measured, metrics);
        String resultHash = sha256(result);
        Map<String, Object> commitment = new LinkedHashMap<>();
        commitment.put("reportId", reportId);
        commitment.put("contractId", id());
        commitment.put("milestoneId", milestoneId);
        commitment.put("tier", "LEVEL_1_5");
        commitment.put("reportFileHash", fileHash);
        commitment.put("resultHash", resultHash);
        commitment.put("sourceTimestamp", confirmedAt);
        commitment.put("actorOrSourceIdentity", INSPECTOR);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", reportId);
        payload.put("contractId", id());
        payload.put("milestoneId", milestoneId);
        payload.put("tier", "LEVEL_1_5");
        payload.put("normalizedResult", result);
        payload.put("resultHash", resultHash);
        payload.put("reportFileHash", fileHash);
        payload.put("reportHash", sha256(commitment));
        payload.put("confirmedAt", confirmedAt);
        payload.put("inspectorId", INSPECTOR);
        return payload;
    }

    private EventEnvelope reportEvent(UUID eventId, Map<String, Object> payload) {
        return new EventEnvelope(eventId, "inspection.report_confirmed", 1, Instant.now(), "inspection-service",
                payload.get("milestoneId").toString(), null, null, null, payload);
    }

    private UUID deliver(Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        reportConsumer.onReportConfirmed(message(reportEvent(eventId, payload)));
        return eventId;
    }

    private int reportCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE contract_id = ?", Integer.class, id());
    }

    private int outboxFor(String type) {
        return events(type).size();
    }

    private Map<String, Object> computed(String caseId) throws Exception {
        String json = jdbc.queryForObject("SELECT computed_resolution FROM breach_case WHERE breach_case_id = ?",
                String.class, caseId);
        return new ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    // ---- mandatory inspection ----

    @Test
    void mandatory_requestsLevel15OnReceipt_blocksTheConfirm_andSettlesOnAConformingReport() throws Exception {
        activate(true);
        delivered(first, "10000", "9500");

        List<JsonNode> requested = events("inspection.level1_5_requested");
        assertThat(requested).hasSize(1);
        FrozenSchemas.assertEvent("inspectionLevel15Requested", requested.get(0));
        assertThat(requested.get(0).at("/payload/reason").asText()).isEqualTo("MANDATORY_INSPECTION");
        assertThat(requested.get(0).at("/payload/initiatedByActorId").isNull()).isTrue();
        assertThat(requested.get(0).at("/payload/milestoneId").asText()).isEqualTo(first.milestoneId());
        assertCode(() -> confirm.execute(confirmCommand(first, BUYER, UUID.randomUUID())),
                ContractConflictException.INSPECTION_REPORT_PENDING);
        assertThat(autoConfirm.execute()).isZero();

        Map<String, Object> payload = reportPayload(first.milestoneId(), "COMPLETE", "10500.000", "10500.000",
                CONFORMING);
        UUID eventId = deliver(payload);

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().path()).isEqualTo(SettlementPath.INSPECTED_CONFORMING);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("150000000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("0.00");
        List<JsonNode> settledEvents = events("milestone.settled");
        assertThat(settledEvents).hasSize(1);
        FrozenSchemas.assertEvent("milestoneSettled", settledEvents.get(0));
        assertThat(settledEvents.get(0).at("/payload/lockedAmount").asText()).isEqualTo("150000000.00");
        assertThat(settledEvents.get(0).at("/payload/actualAmount").asText()).isEqualTo("150000000.00");
        assertThat(outboxFor("milestone.buyer_confirmed")).isZero();
        assertThat(outboxFor("inspection.level1_5_requested")).isEqualTo(1);
        assertThat(outboxFor("breach.reported")).isZero();

        Map<String, Object> report = jdbc.queryForMap("SELECT * FROM inspection_report WHERE milestone_id = ?",
                first.milestoneId());
        assertThat(report).containsEntry("report_id", payload.get("reportId"))
                .containsEntry("tier", "LEVEL_1_5").containsEntry("measurement_status", "COMPLETE")
                .containsEntry("result_hash", payload.get("resultHash"))
                .containsEntry("report_hash", payload.get("reportHash"))
                .containsEntry("received_event_id", eventId.toString());
        assertThat((BigDecimal) report.get("accepted_quantity_kg")).isEqualByComparingTo("10500");
        Map<String, Object> assessment = jdbc.queryForMap("SELECT * FROM quality_assessment WHERE report_id = ?",
                payload.get("reportId"));
        assertThat(assessment).containsEntry("disposition", "CONFORMING");
        assertThat((BigDecimal) assessment.get("final_seller_entitlement")).isEqualByComparingTo("150000000");
        assertThat((BigDecimal) assessment.get("buyer_refund_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) assessment.get("quantity_adjusted_entitlement")).isEqualByComparingTo("150000000");

        // The same event again, and another report for the same milestone: nothing new.
        reportConsumer.onReportConfirmed(message(reportEvent(eventId, payload)));
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "10500.000", "10500.000", CONFORMING));
        assertThat(reportCount()).isEqualTo(1);
        assertThat(outboxFor("milestone.settled")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class,
                eventId.toString())).isEqualTo(1);
    }

    @Test
    void mandatory_partiallyConformingReport_opensASystemCase_contestsTheMilestone_andSettlesNothing()
            throws Exception {
        activate(true);
        delivered(first, "10000", "9800");
        Map<String, Object> payload = reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000",
                PARTIALLY);

        deliver(payload);

        Milestone contested = reload(first);
        assertThat(contested.status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(contested.settlement()).isNull();
        assertThat(contested.flag().issueType()).isEqualTo(FlagIssueType.QUALITY);
        String caseId = contested.flag().breachCaseId();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM breach_case WHERE breach_case_id = ?", caseId);
        assertThat(row).containsEntry("requested_by", "SYSTEM").containsEntry("alleged_breaching_role", "SELLER")
                .containsEntry("breach_reason_code", "QUALITY_BELOW_COMMITTED").containsEntry("severity", "MINOR")
                .containsEntry("status", "REPORTED").containsEntry("milestone_id", first.milestoneId());
        Map<String, Object> resolution = computed(caseId);
        assertThat(resolution).containsEntry("inspectionReportId", payload.get("reportId"))
                .containsEntry("issueType", "QUALITY").containsEntry("qualityDisposition", "PARTIALLY_CONFORMING")
                .containsEntry("sellerEntitlementAmount", "144060000.00")
                .containsEntry("buyerRefundAmount", "5940000.00").containsEntry("prevailingRole", "BUYER")
                .containsEntry("acceptedQuantityKg", "9800");
        assertThat(resolution.get("finalBreachingRole")).isNull();

        List<JsonNode> reported = events("breach.reported");
        assertThat(reported).hasSize(1);
        FrozenSchemas.assertEvent("breachReported", reported.get(0));
        assertThat(reported.get(0).at("/payload/requestedBy").asText()).isEqualTo("SYSTEM");
        assertThat(outboxFor("milestone.settled")).isZero();
        assertThat(outboxFor("milestone.buyer_confirmed")).isZero();
        assertThat(outboxFor("inspection.level1_5_requested")).isEqualTo(1);
        assertThat(contracts.findById(contract.getContractId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
        // A second report while one is stored is ignored.
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", CONFORMING));
        assertThat(reportCount()).isEqualTo(1);
        assertThat(outboxFor("breach.reported")).isEqualTo(1);
    }

    @Test
    void mandatory_nonConformingReport_blamesTheSeller_refundsTheBatch_andNeverSettlesOrChangesTheContract()
            throws Exception {
        activate(true);
        delivered(first, "10000", "9800");

        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", NON_CONFORMING));

        Milestone contested = reload(first);
        assertThat(contested.status()).isEqualTo(MilestoneStatus.CONTESTED);
        Map<String, Object> resolution = computed(contested.flag().breachCaseId());
        assertThat(resolution).containsEntry("qualityDisposition", "NON_CONFORMING")
                .containsEntry("finalBreachingRole", "SELLER").containsEntry("prevailingRole", "BUYER")
                .containsEntry("sellerEntitlementAmount", "0.00")
                .containsEntry("buyerRefundAmount", "150000000.00");
        assertThat(outboxFor("milestone.settled")).isZero();
        assertThat(outboxFor("remedy.finalized")).isZero();
        assertThat(contracts.findById(contract.getContractId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void mandatory_inconclusiveReport_isStored_butNothingMoves_andTheConfirmStaysBlocked() {
        activate(true);
        delivered(first, "10000", "9800");

        deliver(reportPayload(first.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));

        assertThat(reportCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT disposition FROM quality_assessment q JOIN inspection_report r"
                + " ON r.report_id = q.report_id WHERE r.milestone_id = ?", String.class, first.milestoneId()))
                .isEqualTo("INCONCLUSIVE");
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(outboxFor("milestone.settled")).isZero();
        assertThat(outboxFor("breach.reported")).isZero();
        assertCode(() -> confirm.execute(confirmCommand(first, BUYER, UUID.randomUUID())),
                ContractConflictException.INSPECTION_REPORT_PENDING);
    }

    @Test
    void mandatory_theBuyerMayStillFlagInsideTheConfirmWindow_andTheReportThenGoesToTheCase() throws Exception {
        activate(true);
        delivered(first, "10000", "9500");
        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY));
        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        String caseId = reload(first).flag().breachCaseId();

        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", CONFORMING));

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(computed(caseId)).containsEntry("issueType", "QUANTITY").containsEntry("prevailingRole", "SELLER");
        assertThat(outboxFor("milestone.settled")).isZero();
    }

    @Test
    void level15_isRequestedOnReceiptOnlyInMandatoryMode() {
        activate(false);
        delivered(first, "10000", "9500");

        assertThat(outboxFor("inspection.level1_5_requested")).isZero();
        assertThat(outboxFor("milestone.buyer_received")).isEqualTo(1);
    }

    // ---- disputes ----

    @Test
    void qualityDispute_attachesThePartiallyConformingResolution_andOnlyThat() throws Exception {
        activate(false);
        delivered(first, "10000", "9800");
        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUALITY));
        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        String caseId = reload(first).flag().breachCaseId();
        int settledBefore = outboxFor("milestone.settled");

        Map<String, Object> payload = reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000",
                PARTIALLY);
        deliver(payload);

        assertThat(computed(caseId)).containsEntry("inspectionReportId", payload.get("reportId"))
                .containsEntry("issueType", "QUALITY").containsEntry("qualityDisposition", "PARTIALLY_CONFORMING")
                .containsEntry("sellerEntitlementAmount", "144060000.00")
                .containsEntry("buyerRefundAmount", "5940000.00").containsEntry("prevailingRole", "BUYER")
                .containsEntry("finalBreachingRole", null);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(jdbc.queryForObject("SELECT status FROM breach_case WHERE breach_case_id = ?", String.class,
                caseId)).isEqualTo("REPORTED");
        assertThat(outboxFor("milestone.settled")).isEqualTo(settledBefore);
        assertThat(outboxFor("remedy.finalized")).isZero();
        assertThat(outboxFor("inspection.level1_5_requested")).isEqualTo(1);
    }

    @Test
    void quantityDispute_theNearerWeightPrevails_andTheMoneyFollowsTheAcceptedQuantity() throws Exception {
        activate(false);
        delivered(first, "10000", "9500");
        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUANTITY));
        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        String caseId = reload(first).flag().breachCaseId();

        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", CONFORMING));

        // |9800 - 9500| = 300 against |9800 - 10000| = 200: the seller is nearer.
        assertThat(computed(caseId)).containsEntry("issueType", "QUANTITY")
                .containsEntry("qualityDisposition", "CONFORMING").containsEntry("prevailingRole", "SELLER")
                .containsEntry("sellerEntitlementAmount", "147000000.00")
                .containsEntry("buyerRefundAmount", "3000000.00").containsEntry("finalBreachingRole", null);
    }

    // ---- what is ignored, what is refused ----

    @Test
    void aReportThatDoesNotFit_isAcknowledgedAndIgnored_withNothingWritten() {
        activate(true);
        delivered(first, "10000", "9800");
        int outbox = outboxCount();

        // A milestone that is not BUYER_RECEIVED or CONTESTED.
        deliver(reportPayload(second.milestoneId(), "COMPLETE", "9800.000", "9800.000", CONFORMING));
        // Unknown milestone, unknown contract, contract mismatch.
        deliver(reportPayload(UUID.randomUUID().toString(), "COMPLETE", "9800.000", "9800.000", CONFORMING));
        Map<String, Object> unknownContract = reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000",
                CONFORMING);
        unknownContract.put("contractId", UUID.randomUUID().toString());
        unknownContract = resign(unknownContract);
        deliver(unknownContract);
        // LEVEL_2 is not handled.
        Map<String, Object> level2 = reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000",
                CONFORMING);
        level2.put("tier", "LEVEL_2");
        deliver(level2);

        assertThat(reportCount()).isZero();
        assertThat(outboxCount()).isEqualTo(outbox);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
    }

    @Test
    void aReportForAMilestoneThatDoesNotRequireOne_isIgnored() {
        activate(false);
        delivered(first, "10000", "9500");
        int outbox = outboxCount();

        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", CONFORMING));

        assertThat(reportCount()).isZero();
        assertThat(outboxCount()).isEqualTo(outbox);
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
    }

    @Test
    void aBadHash_goesToTheDlq_andNothingIsWritten_andTheEventIsNotClaimed() {
        activate(true);
        delivered(first, "10000", "9800");
        int outbox = outboxCount();

        Map<String, Object> tamperedResult = reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000",
                CONFORMING);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tamperedResult.get("normalizedResult");
        result.put("acceptedQuantityKg", 9900.0);
        assertThatThrownBy(() -> deliver(tamperedResult)).isInstanceOf(InvalidEventPayloadException.class);

        Map<String, Object> tamperedReportHash = reportPayload(first.milestoneId(), "COMPLETE", "9800.000",
                "9800.000", CONFORMING);
        tamperedReportHash.put("reportHash", "f".repeat(64));
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> reportConsumer.onReportConfirmed(message(reportEvent(eventId, tamperedReportHash))))
                .isInstanceOf(InvalidEventPayloadException.class);

        assertThat(reportCount()).isZero();
        assertThat(outboxCount()).isEqualTo(outbox);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class,
                eventId.toString())).isZero();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
    }

    /** The commitment after a payload field was changed on purpose (to reach the use case with a valid hash). */
    private Map<String, Object> resign(Map<String, Object> payload) {
        Map<String, Object> commitment = new LinkedHashMap<>();
        commitment.put("reportId", payload.get("reportId"));
        commitment.put("contractId", payload.get("contractId"));
        commitment.put("milestoneId", payload.get("milestoneId"));
        commitment.put("tier", payload.get("tier"));
        commitment.put("reportFileHash", payload.get("reportFileHash"));
        commitment.put("resultHash", payload.get("resultHash"));
        commitment.put("sourceTimestamp", payload.get("confirmedAt"));
        commitment.put("actorOrSourceIdentity", payload.get("inspectorId"));
        payload.put("reportHash", sha256(commitment));
        return payload;
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
