package com.agricontract.contract.infrastructure.milestone;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.inputMandatoryInspection;
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
import com.agricontract.contract.application.usecase.ApplyInspectionReportUseCase;
import com.agricontract.contract.infrastructure.messaging.InspectionReportConfirmedConsumer;
import com.agricontract.contract.application.usecase.ConfirmMilestoneCleanUseCase;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.usecase.GetBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ContestBreachDecisionUseCase;
import com.agricontract.contract.application.usecase.ExpireContestWindowsUseCase;
import com.agricontract.contract.application.usecase.FinalizeRemedyDecisionUseCase;
import com.agricontract.contract.application.usecase.ListBreachCasesForArbitrationUseCase;
import com.agricontract.contract.application.usecase.ResolveBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ReviewBreachCaseUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.MilestoneRequestInvalidException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;
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
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SettlementPath;
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
        FileScanResultConsumer.class, ApplyInspectionReportUseCase.class, FinalizeRemedyDecisionUseCase.class, InspectionReportConfirmedConsumer.class,
        ContestBreachDecisionUseCase.class, ExpireContestWindowsUseCase.class, RemedyFinalizedIntegrationTest.Fakes.class})
@Testcontainers
class RemedyFinalizedIntegrationTest {
    private static final String BUYER = "0b7e3b7a-1c1e-4c5e-9f0a-3d2b1a0c9e8f";
    private static final String ARBITRATOR = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";

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
    @Autowired FinalizeRemedyDecisionUseCase finalizeRemedy;
    @Autowired ContestBreachDecisionUseCase contestUc;
    @Autowired ExpireContestWindowsUseCase expireWindows;
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
    private String sellerDepositRate = "0.075";

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
        jdbc.update("UPDATE milestone SET funding_status = 'LOCKED', effective_unit_price = 15000.00,"
                + " batch_amount = 150000000, funding_requested_at = ?, funding_locked_at = ?, status = 'IN_PROGRESS'"
                + " WHERE milestone_id = ?", now, now, second.milestoneId());
    }

    private com.agricontract.contract.domain.model.terms.ContractTermsInput terms(boolean mandatory) {
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
        return new com.agricontract.contract.domain.model.terms.ContractTermsInput(
                com.agricontract.contract.domain.model.terms.VndAmount.parse("15000.00"), t.currency(),
                List.of(new com.agricontract.contract.domain.model.terms.MilestoneTerm(1, new BigDecimal("10000"),
                                java.time.LocalDate.of(2036, 12, 15), 2),
                        new com.agricontract.contract.domain.model.terms.MilestoneTerm(2, new BigDecimal("10000"),
                                java.time.LocalDate.of(2037, 1, 20), 2)),
                quality, t.deliveryTerms(), t.inspectionTerms(),
                new com.agricontract.contract.domain.model.terms.PriceBand(
                        com.agricontract.contract.domain.model.terms.VndAmount.parse("14000.00"),
                        com.agricontract.contract.domain.model.terms.VndAmount.parse("16000.00")),
                t.priceAdjustmentRule(), t.toleranceRate(), t.shortfallPenaltyThreshold(), t.buyerPenaltyRate(),
                new BigDecimal("0.08"), t.forceMajeureReportWindowDays(), t.buyerConfirmWindowDays(),
                t.buyerDepositRate(), t.disputeFloorReleaseRate(), new BigDecimal(sellerDepositRate),
                t.level2InspectorOrg(), t.legalProfile(), t.fundingLeadBusinessDays());
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

    private int outboxFor(String type) {
        return events(type).size();
    }

    private Map<String, Object> computed(String caseId) throws Exception {
        String json = jdbc.queryForObject("SELECT computed_resolution FROM breach_case WHERE breach_case_id = ?",
                String.class, caseId);
        return new ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    // ---- mandatory inspection ----

    // ---- disputes ----

    // ---- #137: RemedyDecision and remedy.finalized on the dispute path ----

    /** The buyer flags, the seller contests, the Level 1.5 report arrives: the case holds its computed resolution. */
    private String disputed(Milestone milestone, FlagIssueType type, String sellerKg, String buyerKg, String acceptedKg,
                            String metrics) {
        delivered(milestone, sellerKg, buyerKg);
        flag.execute(flagCommand(milestone, BUYER, UUID.randomUUID(), type));
        respond.execute(respondCommand(milestone, SELLER, UUID.randomUUID()));
        String caseId = reload(milestone).flag().breachCaseId();
        deliver(reportPayload(milestone.milestoneId(), "COMPLETE", acceptedKg, acceptedKg, metrics));
        reviewCase.execute(review(caseId, UUID.randomUUID()));
        return caseId;
    }

    private List<String> remedyEventOrder() {
        return jdbc.queryForList("SELECT event_type FROM event_outbox WHERE event_type IN ('remedy.finalized',"
                + " 'milestone.dispute_resolved', 'milestone.settled') AND JSON_UNQUOTE(JSON_EXTRACT(envelope,"
                + " '$.payload.contractId')) = ? ORDER BY id", String.class, id());
    }

    private Map<String, Object> decisionOf(String caseId) {
        return jdbc.queryForMap("SELECT * FROM remedy_decision WHERE breach_case_id = ?", caseId);
    }

    private List<Map<String, Object>> legsOf(String decisionId) {
        return jdbc.queryForList("SELECT * FROM remedy_leg WHERE remedy_decision_id = ? ORDER BY seq", decisionId);
    }

    @Test
    void partiallyConforming_finalizesAtOnce_settlesTheMilestone_andNeverPublishesMilestoneSettled() throws Exception {
        activate(false);
        String caseId = disputed(first, FlagIssueType.QUALITY, "10000", "9800", "9800.000", PARTIALLY);
        UUID key = UUID.randomUUID();

        resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5, key));

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().path()).isEqualTo(SettlementPath.DISPUTE_RESOLVED);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("144060000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("5940000.00");
        assertThat(contracts.findById(contract.getContractId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");

        Map<String, Object> decision = decisionOf(caseId);
        assertThat(decision).containsEntry("status", "FINALIZED").containsEntry("milestone_id", first.milestoneId())
                .containsEntry("decision_source", "INSPECTOR_L1_5").containsEntry("quality_disposition",
                        "PARTIALLY_CONFORMING").containsEntry("final_breaching_role", null)
                .containsEntry("breach_reason_code", null).containsEntry("settled_milestone_count", 0);
        assertThat(decision.get("finalized_at")).isNotNull();
        assertThat((BigDecimal) decision.get("penalty_amount")).isEqualByComparingTo("0");
        List<Map<String, Object>> legs = legsOf((String) decision.get("remedy_decision_id"));
        assertThat(legs).extracting(l -> l.get("remedy_type"), l -> l.get("fund_type"), l -> l.get("role"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER"),
                        org.assertj.core.groups.Tuple.tuple("PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER"));

        List<JsonNode> finalized = events("remedy.finalized");
        assertThat(finalized).hasSize(1);
        FrozenSchemas.assertEvent("remedyFinalized", finalized.get(0));
        assertThat(finalized.get(0).at("/payload/remedyDecisionId").asText()).isEqualTo(decision.get("remedy_decision_id"));
        assertThat(finalized.get(0).at("/payload/breachCaseId").asText()).isEqualTo(caseId);
        assertThat(finalized.get(0).at("/payload/affectedMilestoneIds/0").asText()).isEqualTo(first.milestoneId());
        assertThat(finalized.get(0).at("/payload/remedyLegs/0/remedyLegId").asText())
                .isEqualTo(legs.get(0).get("remedy_leg_id"));
        assertThat(finalized.get(0).at("/payload/remedyLegs/0/amount").asText()).isEqualTo("144060000.00");
        assertThat(finalized.get(0).at("/payload/buyerId").asText()).isEqualTo(BUYER);
        assertThat(finalized.get(0).at("/payload/sellerId").asText()).isEqualTo(SELLER);
        List<JsonNode> resolved = events("milestone.dispute_resolved");
        assertThat(resolved).hasSize(1);
        FrozenSchemas.assertEvent("milestoneDisputeResolved", resolved.get(0));
        assertThat(resolved.get(0).at("/payload/resolutionFavors").asText()).isEqualTo("BUYER");
        assertThat(resolved.get(0).at("/payload/flaggedByUserId").asText()).isEqualTo(BUYER);
        assertThat(remedyEventOrder()).containsExactly("remedy.finalized", "milestone.dispute_resolved");

        // The same resolve again (same idempotency key): one decision, one pair of events.
        resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5, key));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM remedy_decision WHERE breach_case_id = ?", Integer.class,
                caseId)).isEqualTo(1);
        assertThat(remedyEventOrder()).containsExactly("remedy.finalized", "milestone.dispute_resolved");
    }

    @Test
    void quantityDispute_thatTheSellerWins_settlesOnTheAcceptedQuantity_andFavorsTheSeller() throws Exception {
        activate(false);
        String caseId = disputed(first, FlagIssueType.QUANTITY, "10000", "9500", "9800.000", CONFORMING);

        resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5));

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("147000000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("3000000.00");
        assertThat(settled.settlement().billableQuantityKg()).isEqualByComparingTo("9800");
        assertThat(decisionOf(caseId)).containsEntry("status", "FINALIZED").containsEntry("quality_disposition", "CONFORMING");
        List<JsonNode> finalized = events("remedy.finalized");
        assertThat(finalized).hasSize(1);
        FrozenSchemas.assertEvent("remedyFinalized", finalized.get(0));
        assertThat(finalized.get(0).at("/payload/qualityDisposition").asText()).isEqualTo("CONFORMING");
        assertThat(events("milestone.dispute_resolved").get(0).at("/payload/resolutionFavors").asText())
                .isEqualTo("SELLER");
        assertThat(outboxFor("milestone.settled")).isZero();
    }

    @Test
    void nonConforming_waitsForTheContestWindow_thenFinalizeSettlesAndPublishes_once() throws Exception {
        activate(false);
        String caseId = disputed(first, FlagIssueType.QUALITY, "10000", "9800", "9800.000", NON_CONFORMING);

        resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));

        Map<String, Object> decision = decisionOf(caseId);
        String decisionId = (String) decision.get("remedy_decision_id");
        assertThat(decision).containsEntry("status", "PENDING_CONTEST").containsEntry("final_breaching_role", "SELLER")
                .containsEntry("breach_reason_code", "QUALITY_BELOW_COMMITTED")
                .containsEntry("quality_disposition", "NON_CONFORMING").containsEntry("finalized_at", null);
        assertThat((BigDecimal) decision.get("penalty_amount")).isEqualByComparingTo("12000000");
        assertThat((BigDecimal) decision.get("penalty_debt_amount")).isEqualByComparingTo("0");
        assertThat(legsOf(decisionId)).extracting(l -> l.get("remedy_type"), l -> l.get("fund_type"),
                l -> l.get("role"), l -> ((BigDecimal) l.get("amount")).toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER", "150000000.00"),
                        org.assertj.core.groups.Tuple.tuple("DEPOSIT_FORFEITURE", "SELLER_DEPOSIT", "BUYER", "12000000.00"));
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(reload(first).settlement()).isNull();
        assertThat(outboxFor("remedy.finalized")).isZero();
        assertThat(outboxFor("milestone.dispute_resolved")).isZero();

        finalizeRemedy.execute(decisionId);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(reload(first).settlement().path()).isEqualTo(SettlementPath.DISPUTE_RESOLVED);
        assertThat(reload(first).settlement().sellerAmount().wire()).isEqualTo("0.00");
        assertThat(reload(first).settlement().buyerRefundAmount().wire()).isEqualTo("150000000.00");
        assertThat(decisionOf(caseId)).containsEntry("status", "FINALIZED");
        assertThat(decisionOf(caseId).get("finalized_at")).isNotNull();
        List<JsonNode> finalized = events("remedy.finalized");
        assertThat(finalized).hasSize(1);
        FrozenSchemas.assertEvent("remedyFinalized", finalized.get(0));
        assertThat(finalized.get(0).at("/payload/penaltyEligible").asBoolean()).isTrue();
        assertThat(finalized.get(0).at("/payload/reputationEligible").asBoolean()).isTrue();
        assertThat(finalized.get(0).at("/payload/finalBreachingRole").asText()).isEqualTo("SELLER");
        assertThat(finalized.get(0).at("/payload/remedyLegs")).hasSize(2);
        assertThat(events("milestone.dispute_resolved").get(0).at("/payload/resolutionFavors").asText())
                .isEqualTo("BUYER");
        assertThat(remedyEventOrder()).containsExactly("remedy.finalized", "milestone.dispute_resolved");
        assertThat(contracts.findById(contract.getContractId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");

        // Finalize again: nothing new.
        finalizeRemedy.execute(decisionId);
        assertThat(remedyEventOrder()).containsExactly("remedy.finalized", "milestone.dispute_resolved");
        assertThat(legsOf(decisionId)).hasSize(2);
    }

    @Test
    void aSecondNonConformingMilestone_isCappedByTheRestOfTheSellerDeposit_andTheRestIsDebt() throws Exception {
        activate(false);
        String firstCase = disputed(first, FlagIssueType.QUALITY, "10000", "9800", "9800.000", NON_CONFORMING);
        resolveCase.execute(resolve(firstCase, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));
        String secondCase = disputed(second, FlagIssueType.QUALITY, "10000", "9800", "9800.000", NON_CONFORMING);

        resolveCase.execute(resolve(secondCase, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));

        Map<String, Object> decision = decisionOf(secondCase);
        assertThat((BigDecimal) decision.get("penalty_amount")).isEqualByComparingTo("12000000");
        assertThat((BigDecimal) decision.get("penalty_debt_amount")).isEqualByComparingTo("1500000");
        assertThat(legsOf((String) decision.get("remedy_decision_id"))).extracting(
                l -> l.get("remedy_type"), l -> ((BigDecimal) l.get("amount")).toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PAYMENT_REFUND", "150000000.00"),
                        org.assertj.core.groups.Tuple.tuple("DEPOSIT_FORFEITURE", "10500000.00"));
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(l.amount), 0) FROM remedy_leg l JOIN remedy_decision d"
                + " ON d.remedy_decision_id = l.remedy_decision_id WHERE d.contract_id = ? AND l.fund_type ="
                + " 'SELLER_DEPOSIT'", BigDecimal.class, id())).isEqualByComparingTo("22500000");
    }

    @Test
    void withNoSellerDeposit_thereIsNoForfeitureLeg_andThePenaltyIsDebt() throws Exception {
        sellerDepositRate = "0";
        try {
            activate(false);
            String caseId = disputed(first, FlagIssueType.QUALITY, "10000", "9800", "9800.000", NON_CONFORMING);

            resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));

            Map<String, Object> decision = decisionOf(caseId);
            assertThat((BigDecimal) decision.get("penalty_debt_amount")).isEqualByComparingTo("12000000");
            assertThat(legsOf((String) decision.get("remedy_decision_id"))).extracting(l -> l.get("remedy_type"))
                    .containsExactly("PAYMENT_REFUND");
            finalizeRemedy.execute((String) decision.get("remedy_decision_id"));
            FrozenSchemas.assertEvent("remedyFinalized", events("remedy.finalized").get(0));
        } finally {
            sellerDepositRate = "0.075";
        }
    }

    @Test
    void aCaseWithNoComputedResolution_getsNoRemedyDecision() {
        activate(false);
        String caseId = contractLevelCase("2036-01-01 00:00:00.000000", "REPORTED", false);

        reviewCase.execute(review(caseId, UUID.randomUUID()));
        resolveCase.execute(resolve(caseId, SignerRole.BUYER, DecisionSource.ARBITRATOR));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM remedy_decision WHERE contract_id = ?", Integer.class,
                id())).isZero();
        assertThat(outboxFor("remedy.finalized")).isZero();
    }

    @Test
    void remedyTables_areInsertOnceAndRefuseWhatBreaksTheirChecks() {
        activate(false);
        String caseId = disputedQuietly();
        String decisionId = (String) decisionOf(caseId).get("remedy_decision_id");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO remedy_decision (remedy_decision_id, contract_id,"
                + " breach_case_id, milestone_id, status, decision_source, penalty_amount, penalty_debt_amount,"
                + " settled_milestone_count, created_at) VALUES (?, ?, ?, ?, 'PENDING_CONTEST', 'INSPECTOR_L1_5', 0, 0,"
                + " 0, NOW(6))", UUID.randomUUID().toString(), id(), caseId, first.milestoneId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO remedy_leg (remedy_leg_id, remedy_decision_id, seq,"
                + " remedy_type, fund_type, role, amount) VALUES (?, ?, 9, 'PAYMENT_REFUND', 'MILESTONE_PAYMENT',"
                + " 'BUYER', 0)", UUID.randomUUID().toString(), decisionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE remedy_decision SET status = 'FINALIZED' WHERE"
                + " remedy_decision_id = ?", decisionId)).isInstanceOf(DataAccessException.class);
    }

    private String disputedQuietly() {
        String caseId = disputed(first, FlagIssueType.QUALITY, "10000", "9800", "9800.000", NON_CONFORMING);
        resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));
        return caseId;
    }

    // ---- #241: the contest window, the contest, the second Level 1.5 report ----

    private List<Map<String, Object>> decisionsOf(String caseId) {
        return jdbc.queryForList("SELECT * FROM remedy_decision WHERE breach_case_id = ? ORDER BY `round`", caseId);
    }

    private BreachCase caseOf(String caseId) {
        return breachCases.findById(caseId).orElseThrow();
    }

    private BreachCaseCommands.Contest contest(String caseId, String actor, List<String> files) {
        return new BreachCaseCommands.Contest(id(), caseId, actor, UUID.randomUUID(), "the sample was not mine", files);
    }

    private void windowPassed(String caseId) {
        jdbc.update("UPDATE breach_case SET contest_deadline = ? WHERE breach_case_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), caseId);
    }

    private void secondReport(Milestone milestone, String accepted, String metrics) {
        deliver(reportPayload(milestone.milestoneId(), "COMPLETE", accepted, accepted, metrics));
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED));
    }

    @Test
    void aNonConformingDecision_opensTheWindow_andTheSchedulerFinalizesItOncePassed() {
        activate(false);
        String caseId = disputedQuietly();
        BreachCase open = caseOf(caseId);

        assertThat(open.contestStatus()).isEqualTo(ContestStatus.OPEN);
        assertThat(open.contestDeadline()).isEqualTo(calendar.deadline(open.resolvedAt(), 2));
        assertThat(expireWindows.execute()).isZero();
        assertThat(decisionOf(caseId)).containsEntry("status", "PENDING_CONTEST");

        windowPassed(caseId);
        assertThat(expireWindows.execute()).isEqualTo(1);

        assertThat(caseOf(caseId).contestStatus()).isEqualTo(ContestStatus.EXPIRED);
        assertThat(decisionOf(caseId)).containsEntry("status", "FINALIZED");
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SETTLED);
        List<JsonNode> finalized = events("remedy.finalized");
        assertThat(finalized).hasSize(1);
        assertThat(finalized.get(0).at("/payload/remedyLegs").findValuesAsText("amount"))
                .containsExactly("150000000.00", "12000000.00");
        assertThat(expireWindows.execute()).isZero();
        assertThat(events("remedy.finalized")).hasSize(1);
    }

    @Test
    void aContest_holdsTheDecision_andASecondConformingReportSettlesForTheSeller() throws Exception {
        activate(false);
        String caseId = disputedQuietly();
        String firstDecision = (String) decisionOf(caseId).get("remedy_decision_id");

        contestUc.contest(contest(caseId, SELLER, List.of()));

        BreachCase contested = caseOf(caseId);
        assertThat(contested.contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(contested.contestReason()).isEqualTo("the sample was not mine");
        assertThat(contested.contestedByActorId()).isEqualTo(SELLER);
        assertThat(contested.contestedAt()).isNotNull();
        assertThat(decisionOf(caseId)).containsEntry("status", "PENDING_CONTEST");
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        List<JsonNode> requested = events("inspection.level1_5_requested");
        assertThat(requested).extracting(e -> e.at("/payload/reason").asText())
                .containsExactly("DISPUTE_ROUTED", "DECISION_CONTESTED");
        FrozenSchemas.assertEvent("inspectionLevel15Requested", requested.get(1));
        assertThat(requested.get(1).at("/payload/initiatedByActorId").asText()).isEqualTo(SELLER);

        // The scheduler loses the race: the contest came first.
        windowPassed(caseId);
        assertThat(expireWindows.execute()).isZero();
        assertThat(caseOf(caseId).contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertConflict(() -> contestUc.contest(contest(caseId, SELLER, List.of())));

        secondReport(first, "10000.000", CONFORMING);

        BreachCase underReview = caseOf(caseId);
        assertThat(underReview.status().name()).isEqualTo("UNDER_REVIEW");
        assertThat(underReview.contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(underReview.finalBreachingRole()).isNull();
        assertThat(underReview.computedResolution().qualityDisposition().name()).isEqualTo("CONFORMING");
        assertThat(decisionOf(caseId)).containsEntry("status", "SUPERSEDED");
        assertThat(decisionOf(caseId).get("superseded_at")).isNotNull();
        assertThat(jdbc.queryForList("SELECT `round` FROM inspection_report WHERE milestone_id = ? ORDER BY `round`",
                Integer.class, first.milestoneId())).containsExactly(1, 2);
        assertThat(outboxFor("remedy.finalized")).isZero();

        resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5));

        List<Map<String, Object>> decisions = decisionsOf(caseId);
        assertThat(decisions).extracting(d -> d.get("status"), d -> d.get("round"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SUPERSEDED", 1),
                        org.assertj.core.groups.Tuple.tuple("FINALIZED", 2));
        assertThat(decisions.get(1).get("remedy_decision_id")).isNotEqualTo(firstDecision);
        assertThat(legsOf((String) decisions.get(1).get("remedy_decision_id"))).extracting(l -> l.get("remedy_type"),
                l -> ((BigDecimal) l.get("amount")).toPlainString()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PAYMENT_SETTLEMENT", "150000000.00"));
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(reload(first).settlement().sellerAmount().wire()).isEqualTo("150000000.00");
        assertThat(caseOf(caseId).contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        List<JsonNode> finalized = events("remedy.finalized");
        assertThat(finalized).hasSize(1);
        assertThat(finalized.get(0).at("/payload/remedyDecisionId").asText()).isEqualTo(decisions.get(1).get("remedy_decision_id"));
        assertThat(finalized.get(0).at("/payload/penaltyEligible").asBoolean()).isFalse();
        assertThat(events("milestone.dispute_resolved").get(0).at("/payload/resolutionFavors").asText())
                .isEqualTo("SELLER");
        assertThat(expireWindows.execute()).isZero();
    }

    @Test
    void aSecondNonConformingReport_isFinalAtOnce_withNoNewWindow_andTheForfeitureIsTakenOnce() {
        activate(false);
        String caseId = disputedQuietly();
        contestUc.contest(contest(caseId, SELLER, List.of()));
        secondReport(first, "9800.000", NON_CONFORMING);

        resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));

        List<Map<String, Object>> decisions = decisionsOf(caseId);
        assertThat(decisions).extracting(d -> d.get("status")).containsExactly("SUPERSEDED", "FINALIZED");
        assertThat(legsOf((String) decisions.get(1).get("remedy_decision_id"))).extracting(l -> l.get("remedy_type"),
                l -> ((BigDecimal) l.get("amount")).toPlainString()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PAYMENT_REFUND", "150000000.00"),
                org.assertj.core.groups.Tuple.tuple("DEPOSIT_FORFEITURE", "12000000.00"));
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(caseOf(caseId).contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(events("remedy.finalized")).hasSize(1);
        assertThat(events("remedy.finalized").get(0).at("/payload/penaltyEligible").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(l.amount), 0) FROM remedy_leg l JOIN remedy_decision d"
                + " ON d.remedy_decision_id = l.remedy_decision_id WHERE d.contract_id = ? AND l.fund_type ="
                + " 'SELLER_DEPOSIT' AND d.status <> 'SUPERSEDED'", BigDecimal.class, id()))
                .isEqualByComparingTo("12000000");
        assertThat(expireWindows.execute()).isZero();
    }

    @Test
    void contest_isRefusedForTheWrongParty_afterTheDeadline_twice_andOnAContractLevelCase() {
        activate(false);
        String caseId = disputedQuietly();

        assertThatThrownBy(() -> contestUc.requireContester(id(), caseId, BUYER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> contestUc.contest(contest(caseId, BUYER, List.of())))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> contestUc.contest(contest(caseId, SELLER, List.of(UUID.randomUUID().toString()))))
                .isInstanceOfSatisfying(MilestoneRequestInvalidException.class,
                        e -> assertThat(e.getCode()).isEqualTo(MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED));
        windowPassed(caseId);
        assertConflict(() -> contestUc.contest(contest(caseId, SELLER, List.of())));
        assertThat(caseOf(caseId).contestStatus()).isEqualTo(ContestStatus.OPEN);
        assertThat(outboxFor("inspection.level1_5_requested")).isEqualTo(1);

        String contractLevel = contractLevelCase("2036-01-01 00:00:00.000000", "RESOLVED", false);
        jdbc.update("UPDATE breach_case SET final_breaching_role = 'SELLER' WHERE breach_case_id = ?", contractLevel);
        assertThat(caseOf(contractLevel).contestStatus()).isEqualTo(ContestStatus.NOT_APPLICABLE);
        assertConflict(() -> contestUc.contest(contest(contractLevel, SELLER, List.of())));
    }

    @Test
    void contestEvidence_isTheContestersOwn_dedupes_andClosesWithTheSecondReport() {
        activate(false);
        String fileA = uploadReady(SELLER, first);
        String fileB = uploadReady(SELLER, first);
        String caseId = disputedQuietly();
        BreachCaseCommands.AddContestEvidence early = new BreachCaseCommands.AddContestEvidence(id(), caseId, SELLER,
                UUID.randomUUID(), List.of(fileA));
        assertConflict(() -> contestUc.addEvidence(early));

        contestUc.contest(contest(caseId, SELLER, List.of(fileA)));
        assertThat(caseOf(caseId).contestEvidenceFileIds()).containsExactly(fileA);
        assertThat(fileStore.confirmed).contains(fileA);

        contestUc.addEvidence(new BreachCaseCommands.AddContestEvidence(id(), caseId, SELLER, UUID.randomUUID(),
                List.of(fileA, fileB)));
        contestUc.addEvidence(new BreachCaseCommands.AddContestEvidence(id(), caseId, SELLER, UUID.randomUUID(),
                List.of(fileB)));
        assertThat(caseOf(caseId).contestEvidenceFileIds()).containsExactly(fileA, fileB);
        assertThatThrownBy(() -> contestUc.requireContestOwner(id(), caseId, BUYER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> contestUc.addEvidence(new BreachCaseCommands.AddContestEvidence(id(), caseId, BUYER,
                UUID.randomUUID(), List.of(fileA)))).isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> contestUc.addEvidence(new BreachCaseCommands.AddContestEvidence(id(), caseId, SELLER,
                UUID.randomUUID(), List.of(UUID.randomUUID().toString()))))
                .isInstanceOf(MilestoneRequestInvalidException.class);

        secondReport(first, "10000.000", CONFORMING);

        assertConflict(() -> contestUc.addEvidence(new BreachCaseCommands.AddContestEvidence(id(), caseId, SELLER,
                UUID.randomUUID(), List.of(fileA))));
        assertThat(caseOf(caseId).contestEvidenceFileIds()).containsExactly(fileA, fileB);
    }

    @Test
    void anInconclusiveFirstReport_asksAgainWithTheSameReason_andASecondOneIsFinal_withNoMoney() {
        activate(false);
        delivered(first, "10000", "9800");
        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUALITY));
        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        String caseId = reload(first).flag().breachCaseId();

        deliver(reportPayload(first.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));

        assertThat(events("inspection.level1_5_requested")).extracting(e -> e.at("/payload/reason").asText())
                .containsExactly("DISPUTE_ROUTED", "DISPUTE_ROUTED");
        assertThat(caseOf(caseId).computedResolution()).isNull();

        deliver(reportPayload(first.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", NON_CONFORMING));

        assertThat(events("inspection.level1_5_requested")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ?", Integer.class,
                first.milestoneId())).isEqualTo(2);
        assertThat(caseOf(caseId).computedResolution()).isNull();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(outboxFor("remedy.finalized")).isZero();
    }

    @Test
    void mandatory_anInconclusiveFirstReport_asksAgainAsAMandatoryInspection_andARerunSettles() {
        activate(true);
        delivered(first, "10000", "9800");
        assertThat(events("inspection.level1_5_requested")).hasSize(1);

        deliver(reportPayload(first.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));

        assertThat(events("inspection.level1_5_requested")).extracting(e -> e.at("/payload/reason").asText())
                .containsExactly("MANDATORY_INSPECTION", "MANDATORY_INSPECTION");
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);

        secondReport(first, "9800.000", CONFORMING);

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SETTLED);
    }

    @Test
    void aSecondReport_isIgnored_whenTheFirstDecidedAndNobodyContested() {
        activate(false);
        String caseId = disputed(first, FlagIssueType.QUALITY, "10000", "9800", "9800.000", PARTIALLY);

        secondReport(first, "9800.000", NON_CONFORMING);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ?", Integer.class,
                first.milestoneId())).isEqualTo(1);
        assertThat(caseOf(caseId).computedResolution().qualityDisposition().name()).isEqualTo("PARTIALLY_CONFORMING");
    }

    // ---- what is ignored, what is refused ----

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
    // ---- #593 (D91): contest evidence uploads, and the INCONCLUSIVE re-run outside the contest ----

    private static void assertNotYourTurn(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED));
    }

    @Test
    void contestEvidence_isUploadedByTheBlamedPartyWhileOpen_thenByTheContester_untilTheContestReport() {
        activate(false);
        String caseId = disputedQuietly();

        String beforeContest = uploadReady(SELLER, first);
        assertNotYourTurn(() -> uploadFile(BUYER, first, UUID.randomUUID()));

        contestUc.contest(contest(caseId, SELLER, List.of(beforeContest)));
        String topUp = uploadReady(SELLER, first);
        assertNotYourTurn(() -> uploadFile(BUYER, first, UUID.randomUUID()));
        contestUc.addEvidence(new BreachCaseCommands.AddContestEvidence(id(), caseId, SELLER, UUID.randomUUID(),
                List.of(topUp)));
        assertThat(caseOf(caseId).contestEvidenceFileIds()).containsExactly(beforeContest, topUp);

        secondReport(first, "10000.000", CONFORMING);

        assertNotYourTurn(() -> uploadFile(SELLER, first, UUID.randomUUID()));
    }

    @Test
    void contestEvidence_cannotBeUploadedOnceTheWindowPassed() {
        activate(false);
        String caseId = disputedQuietly();
        windowPassed(caseId);

        assertNotYourTurn(() -> uploadFile(SELLER, first, UUID.randomUUID()));
    }

    @Test
    void anInconclusiveRerun_doesNotUseUpTheContest_soAThirdReportDecides() throws Exception {
        activate(false);
        delivered(first, "10000", "9800");
        flag.execute(flagCommand(first, BUYER, UUID.randomUUID(), FlagIssueType.QUALITY));
        respond.execute(respondCommand(first, SELLER, UUID.randomUUID()));
        String caseId = reload(first).flag().breachCaseId();
        deliver(reportPayload(first.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", NON_CONFORMING));
        reviewCase.execute(review(caseId, UUID.randomUUID()));
        resolveCase.execute(resolve(caseId, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5));
        assertThat(caseOf(caseId).contestStatus()).isEqualTo(ContestStatus.OPEN);

        contestUc.contest(contest(caseId, SELLER, List.of()));
        assertThat(events("inspection.level1_5_requested")).extracting(e -> e.at("/payload/reason").asText())
                .containsExactly("DISPUTE_ROUTED", "DISPUTE_ROUTED", "DECISION_CONTESTED");

        secondReport(first, "10000.000", CONFORMING);

        assertThat(jdbc.queryForList("SELECT purpose FROM inspection_report WHERE milestone_id = ? ORDER BY `round`",
                String.class, first.milestoneId())).containsExactly("INITIAL", "RERUN", "CONTEST");
        assertThat(caseOf(caseId).status().name()).isEqualTo("UNDER_REVIEW");
        assertThat(decisionOf(caseId)).containsEntry("status", "SUPERSEDED");

        resolveCase.execute(resolve(caseId, null, DecisionSource.INSPECTOR_L1_5));

        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(reload(first).settlement().sellerAmount().wire()).isEqualTo("150000000.00");
        assertThat(events("remedy.finalized")).hasSize(1);

        // A fourth report is ignored.
        secondReport(first, "9000.000", NON_CONFORMING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ?", Integer.class,
                first.milestoneId())).isEqualTo(3);
    }

    private void seedComputed(String caseId, String finalRole, String disposition) {
        String json = "{\"inspectionReportId\":\"" + UUID.randomUUID() + "\",\"issueType\":\"QUANTITY\","
                + "\"acceptedQuantityKg\":\"9000\",\"qualityDisposition\":\"" + disposition + "\","
                + "\"finalBreachingRole\":" + (finalRole == null ? "null" : "\"" + finalRole + "\"") + ","
                + "\"decisionSource\":\"INSPECTOR_L1_5\",\"sellerEntitlementAmount\":\"855000000.00\","
                + "\"buyerRefundAmount\":\"47500000.00\",\"prevailingRole\":\"BUYER\"}";
        jdbc.update("UPDATE breach_case SET computed_resolution = ? WHERE breach_case_id = ?", json, caseId);
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
