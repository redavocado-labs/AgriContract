package com.agricontract.contract.infrastructure.milestone;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.inputMandatoryInspection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.dto.DisputeExitCommands;
import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.dto.OtpEmail;
import com.agricontract.contract.application.dto.SignChallengeView;
import com.agricontract.contract.application.dto.UserInfo;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.OtpEmailPort;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.port.UserPort;
import com.agricontract.contract.application.service.DisputeExitStep;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.application.service.Level15Inspections;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.application.service.MilestoneContestStep;
import com.agricontract.contract.application.service.MilestoneFunding;
import com.agricontract.contract.application.service.MilestoneReadAccess;
import com.agricontract.contract.application.service.OtpChallengeIssuer;
import com.agricontract.contract.application.service.RemedySettlement;
import com.agricontract.contract.application.service.SignerEligibility;
import com.agricontract.contract.application.usecase.ApplyInspectionReportUseCase;
import com.agricontract.contract.application.usecase.ContestBreachDecisionUseCase;
import com.agricontract.contract.application.usecase.DisputeExitUseCases;
import com.agricontract.contract.application.usecase.FlagMilestoneUseCase;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.usecase.RecordBuyerReceiptUseCase;
import com.agricontract.contract.application.usecase.RecordFileScanResultUseCase;
import com.agricontract.contract.application.usecase.RecordLevel15AssignmentUseCase;
import com.agricontract.contract.application.usecase.RecordSellerWeighedUseCase;
import com.agricontract.contract.application.usecase.ResolveBreachCaseUseCase;
import com.agricontract.contract.application.usecase.RespondToMilestoneFlagUseCase;
import com.agricontract.contract.application.usecase.ReviewBreachCaseUseCase;
import com.agricontract.contract.application.usecase.UploadMilestoneEvidenceUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.dispute.DisputeExitType;
import com.agricontract.contract.domain.model.milestone.BuyerReceiptConsent;
import com.agricontract.contract.domain.model.milestone.ContestFlagConsent;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.FlagMilestoneConsent;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SellerWeighConsent;
import com.agricontract.contract.domain.model.milestone.SettlementPath;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.terms.ContractTemplateProfile;
import com.agricontract.contract.domain.model.terms.ContractTermsRules;
import com.agricontract.contract.domain.model.terms.ContractTermsSnapshot;
import com.agricontract.contract.domain.model.terms.TermsCanonicalizer;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.infrastructure.config.BusinessCalendarConfiguration;
import com.agricontract.contract.infrastructure.config.MilestoneDeliveryConfiguration;
import com.agricontract.contract.infrastructure.consent.ConsentConfiguration;
import com.agricontract.contract.infrastructure.contract.FrozenSchemas;
import com.agricontract.contract.infrastructure.messaging.FileScanResultConsumer;
import com.agricontract.contract.infrastructure.messaging.InspectionReportConfirmedConsumer;
import com.agricontract.contract.infrastructure.messaging.Level15AssignedConsumer;
import com.agricontract.contract.infrastructure.messaging.OutboxContractEventPublisher;
import com.agricontract.contract.infrastructure.otp.OtpConfiguration;
import com.agricontract.contract.infrastructure.persistence.SpringTransactionRunner;
import com.agricontract.contract.infrastructure.persistence.breach.JdbcBreachCaseRepository;
import com.agricontract.contract.infrastructure.persistence.consent.JdbcConsentEvidenceRepository;
import com.agricontract.contract.infrastructure.persistence.dispute.JdbcDisputeExitChallengeRepository;
import com.agricontract.contract.infrastructure.persistence.dispute.JdbcSettlementProposalRepository;
import com.agricontract.contract.infrastructure.persistence.idempotency.JdbcIdempotencyStore;
import com.agricontract.contract.infrastructure.persistence.inspection.JdbcInspectionReportRepository;
import com.agricontract.contract.infrastructure.persistence.inspection.JdbcLevel15AssignmentRepository;
import com.agricontract.contract.infrastructure.persistence.inspection.JdbcLevel15RequestRepository;
import com.agricontract.contract.infrastructure.persistence.mapper.ContractMapper;
import com.agricontract.contract.infrastructure.persistence.milestone.JdbcMilestoneEvidenceRepository;
import com.agricontract.contract.infrastructure.persistence.milestone.JdbcMilestoneRepository;
import com.agricontract.contract.infrastructure.persistence.milestone.JdbcSellerWeighDeclarationRepository;
import com.agricontract.contract.infrastructure.persistence.remedy.JdbcRemedyDecisionRepository;
import com.agricontract.contract.infrastructure.persistence.repository.ContractRepositoryImpl;
import com.agricontract.contract.infrastructure.persistence.signature.JdbcOtpChallengeRepository;
import com.agricontract.contract.infrastructure.persistence.signature.JdbcSignatureRepository;
import com.agricontract.contract.infrastructure.terms.ContractTermsConfiguration;
import com.agricontract.contract.infrastructure.terms.JcsTermsCanonicalizer;
import com.agricontract.contract.infrastructure.web.dto.MilestoneDetailResponse;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * #494 on MySQL (D57, D92): a milestone dispute ended by a concession or an agreed settlement, through the real
 * repositories, V26, the outbox and the OTP flow. P 15,000 per kg, locked batch 150,000,000, seller penalty 8 % of
 * the milestone (12,000,000), seller deposit 22,500,000 (the #137 examples).
 */
@DataJpaTest(properties = {"agricontract.eventing.outbox.enabled=false", "contract.otp.resend-cooldown-seconds=0",
        "contract.otp.max-sends-per-hour=1000"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, EventingAutoConfiguration.class})
@Import({BusinessCalendarConfiguration.class, SpringTransactionRunner.class, MilestoneFunding.class,
        JdbcMilestoneRepository.class, JdbcInspectionReportRepository.class, JdbcBreachCaseRepository.class,
        ReviewBreachCaseUseCase.class, ResolveBreachCaseUseCase.class, JdbcRemedyDecisionRepository.class,
        RemedySettlement.class, FlagMilestoneUseCase.class, RespondToMilestoneFlagUseCase.class,
        MilestoneContestStep.class, OutboxContractEventPublisher.class, ContractRepositoryImpl.class,
        JdbcSignatureRepository.class, ContractMapper.class, JcsTermsCanonicalizer.class,
        ContractTermsConfiguration.class, ConsentConfiguration.class, MilestoneDeliveryConfiguration.class,
        JdbcMilestoneEvidenceRepository.class, JdbcSellerWeighDeclarationRepository.class,
        JdbcConsentEvidenceRepository.class, JdbcIdempotencyStore.class, UploadMilestoneEvidenceUseCase.class,
        RecordSellerWeighedUseCase.class, RecordBuyerReceiptUseCase.class, MilestoneCleanSettlement.class,
        EvidenceAttachment.class, RecordFileScanResultUseCase.class, MilestoneReadAccess.class,
        GetMilestoneDetailUseCase.class, FileScanResultConsumer.class, ApplyInspectionReportUseCase.class,
        InspectionReportConfirmedConsumer.class, ContestBreachDecisionUseCase.class, Level15Inspections.class,
        DisputeExitStep.class, DisputeExitUseCases.class, JdbcLevel15RequestRepository.class,
        JdbcLevel15AssignmentRepository.class, JdbcSettlementProposalRepository.class,
        JdbcDisputeExitChallengeRepository.class, RecordLevel15AssignmentUseCase.class, Level15AssignedConsumer.class,
        SignerEligibility.class, OtpChallengeIssuer.class, JdbcOtpChallengeRepository.class, OtpConfiguration.class,
        DisputeExitIntegrationTest.Fakes.class})
@Testcontainers
class DisputeExitIntegrationTest {
    private static final String BUYER = "0b7e3b7a-1c1e-4c5e-9f0a-3d2b1a0c9e8f";
    private static final String SELLER = "5a4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a";
    private static final String ARBITRATOR = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
    private static final String INSPECTOR = "6e5d4c3b-2a19-4f8e-9d7c-0b1a2c3d4e5f";
    private static final String CONFORMING = "12.9/0.5/4.0";
    private static final String PARTIALLY = "13.5/0.8/7.5";
    private static final String NON_CONFORMING = "15.0/0.5/5.0";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    static class FakeFileStore implements EvidenceFileStore {
        @Override
        public StoredFile storeOnBehalfOf(String actingUserId, String idempotencyKey, String contentType,
                                          long contentLength, InputStream body) {
            return new StoredFile(UUID.nameUUIDFromBytes((actingUserId + "|" + idempotencyKey)
                    .getBytes(StandardCharsets.UTF_8)).toString(), EvidenceScanStatus.PROCESSING);
        }

        @Override
        public void confirmAttached(String fileId) {
        }

        @Override
        public FileContent read(String fileId) {
            byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
            return new FileContent("image/jpeg", bytes.length, new ByteArrayInputStream(bytes));
        }
    }

    @TestConfiguration
    static class Fakes {
        static final List<OtpEmail> sent = Collections.synchronizedList(new ArrayList<>());

        @Bean
        FakeFileStore fakeFileStore() {
            return new FakeFileStore();
        }

        @Bean
        UserPort userPort() {
            return new UserPort() {
                @Override
                public UserInfo getUser(String userId) {
                    return findUser(userId).orElse(null);
                }

                @Override
                public Optional<UserInfo> findUser(String userId) {
                    return Optional.of(new UserInfo(userId, "HTX", userId + "@x.test",
                            userId.equals(BUYER) ? "BUYER" : "SELLER", "VERIFIED", null, null));
                }
            };
        }

        @Bean
        OtpEmailPort otpEmailPort() {
            return sent::add;
        }
    }

    @Autowired UploadMilestoneEvidenceUseCase upload;
    @Autowired RecordSellerWeighedUseCase weigh;
    @Autowired RecordBuyerReceiptUseCase receive;
    @Autowired FlagMilestoneUseCase flag;
    @Autowired RespondToMilestoneFlagUseCase respond;
    @Autowired ReviewBreachCaseUseCase reviewCase;
    @Autowired ResolveBreachCaseUseCase resolveCase;
    @Autowired ContestBreachDecisionUseCase contestUc;
    @Autowired DisputeExitUseCases exits;
    @Autowired GetMilestoneDetailUseCase detail;
    @Autowired FileScanResultConsumer scans;
    @Autowired InspectionReportConfirmedConsumer reportConsumer;
    @Autowired Level15AssignedConsumer assignedConsumer;
    @Autowired MilestoneFunding funding;
    @Autowired JdbcMilestoneRepository milestones;
    @Autowired ContractRepositoryImpl contracts;
    @Autowired TransactionRunner transactions;
    @Autowired TermsCanonicalizer canonicalizer;
    @Autowired ContractTermsRules rules;
    @Autowired ContractTemplateProfile template;
    @Autowired JdbcTemplate jdbc;

    private Contract contract;
    private Milestone first;
    private Milestone second;

    @BeforeEach
    void clearEmails() {
        Fakes.sent.clear();
    }

    // ---- concession while the flag waits for the seller ----

    @Test
    void sellerConcedesAQuantityFlag_whileAwaiting_caseOpenedAndResolvedMutual_noFault_buyerFigure() throws Exception {
        activate(false);
        flagged(first, FlagIssueType.QUANTITY, "10000", "9800");

        MilestoneDetailResponse before = detailOf(first, BUYER, "BUYER");
        assertThat(before.disputeExit()).isNotNull();
        assertThat(before.disputeExit().buyerPositionAmount()).isEqualTo("147000000.00");
        assertThat(before.disputeExit().sellerPositionAmount()).isEqualTo("150000000.00");
        assertThat(before.disputeExit().settlementAllowed()).isFalse();

        concede(first, SELLER);

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().path()).isEqualTo(SettlementPath.DISPUTE_RESOLVED);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("147000000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("3000000.00");
        String caseId = settled.flag().breachCaseId();
        assertThat(jdbc.queryForMap("SELECT status, decision_source, final_breaching_role FROM breach_case"
                + " WHERE breach_case_id = ?", caseId)).containsEntry("status", "RESOLVED")
                .containsEntry("decision_source", "MUTUAL").containsEntry("final_breaching_role", null);
        assertThat(decisionOf(caseId)).containsEntry("status", "FINALIZED").containsEntry("decision_source", "MUTUAL")
                .containsEntry("final_breaching_role", null).containsEntry("inspection_fee_party", null);
        assertThat(events("breach.reported")).hasSize(1);
        List<JsonNode> finalized = events("remedy.finalized");
        assertThat(finalized).hasSize(1);
        FrozenSchemas.assertEvent("remedyFinalized", finalized.get(0));
        assertThat(finalized.get(0).at("/payload/decisionSource").asText()).isEqualTo("MUTUAL");
        assertThat(finalized.get(0).at("/payload/reputationEligible").asBoolean()).isFalse();
        assertThat(finalized.get(0).at("/payload/remedyLegs")).extracting(l -> l.get("remedyType").asText(),
                l -> l.get("amount").asText()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PAYMENT_SETTLEMENT", "147000000.00"),
                org.assertj.core.groups.Tuple.tuple("PAYMENT_REFUND", "3000000.00"));
        List<JsonNode> resolved = events("milestone.dispute_resolved");
        assertThat(resolved).hasSize(1);
        FrozenSchemas.assertEvent("milestoneDisputeResolved", resolved.get(0));
        assertThat(resolved.get(0).at("/payload/resolutionFavors").asText()).isEqualTo("BUYER");
        assertThat(events("inspection.level1_5_cancelled")).isEmpty();
        assertThat(events("milestone.settled")).isEmpty();
        assertThat(Fakes.sent).extracting(OtpEmail::purpose).containsExactly(OtpEmail.CONCEDE_DISPUTE);
        assertThat(detailOf(first, BUYER, "BUYER").disputeExit()).isNull();
    }

    @Test
    void buyerWithdrawsTheFlag_isAConcession_onTheSellersPosition() throws Exception {
        activate(false);
        flagged(first, FlagIssueType.QUANTITY, "10000", "9800");

        concede(first, BUYER);

        Milestone settled = reload(first);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("150000000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("0.00");
        assertThat(events("milestone.dispute_resolved").get(0).at("/payload/resolutionFavors").asText())
                .isEqualTo("SELLER");
        assertThat(events("remedy.finalized").get(0).at("/payload/finalBreachingRole").isNull()).isTrue();
    }

    @Test
    void settlementWhileAwaiting_isNotAllowed() {
        activate(false);
        flagged(first, FlagIssueType.QUANTITY, "10000", "9800");

        assertExitNotAllowed(() -> initiate(DisputeExitType.SETTLE, first, BUYER, "148000000.00"));
    }

    // ---- concession of a contested flag ----

    @Test
    void sellerConcedesAQualityFlagWhileContested_nonConformingWithPenalty_cancelsTheInspection_lapsesTheProposal()
            throws Exception {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        settle(first, BUYER, "100000000.00");
        assertThat(openProposals(first)).isEqualTo(1);
        assertThat(detailOf(first, SELLER, "SELLER").disputeExit().openProposal().proposedByRole())
                .isEqualTo("BUYER");

        concede(first, SELLER);

        String caseId = reload(first).flag().breachCaseId();
        Map<String, Object> decision = decisionOf(caseId);
        assertThat(decision).containsEntry("status", "FINALIZED").containsEntry("final_breaching_role", "SELLER")
                .containsEntry("quality_disposition", "NON_CONFORMING").containsEntry("decision_source", "MUTUAL");
        assertThat((BigDecimal) decision.get("penalty_amount")).isEqualByComparingTo("12000000");
        List<JsonNode> finalized = events("remedy.finalized");
        FrozenSchemas.assertEvent("remedyFinalized", finalized.get(0));
        assertThat(finalized.get(0).at("/payload/reputationEligible").asBoolean()).isTrue();
        assertThat(finalized.get(0).at("/payload/penaltyEligible").asBoolean()).isTrue();
        assertThat(finalized.get(0).at("/payload/remedyLegs")).extracting(l -> l.get("remedyType").asText(),
                l -> l.get("amount").asText()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PAYMENT_REFUND", "150000000.00"),
                org.assertj.core.groups.Tuple.tuple("DEPOSIT_FORFEITURE", "12000000.00"));
        List<JsonNode> cancelled = events("inspection.level1_5_cancelled");
        assertThat(cancelled).hasSize(1);
        FrozenSchemas.assertEvent("inspectionLevel15Cancelled", cancelled.get(0));
        assertThat(cancelled.get(0).at("/payload/reason").asText()).isEqualTo("DISPUTE_CONCEDED");
        assertThat(jdbc.queryForList("SELECT end_reason FROM level1_5_request WHERE milestone_id = ?", String.class,
                first.milestoneId())).containsExactly("CANCELLED");
        assertThat(jdbc.queryForList("SELECT status FROM dispute_settlement_proposal WHERE milestone_id = ?",
                String.class, first.milestoneId())).containsExactly("LAPSED");
        assertThat(reload(first).settlement().billableQuantityKg()).isEqualByComparingTo("0");
    }

    @Test
    void aLateReportAfterTheExit_isIgnored() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        concede(first, BUYER);

        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", NON_CONFORMING));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ?",
                Integer.class, first.milestoneId())).isZero();
        assertThat(events("remedy.finalized")).hasSize(1);
    }

    @Test
    void verifyReplay_isANoOp_withNoSecondEvent() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        SignChallengeView challenge = initiate(DisputeExitType.CONCEDE, first, BUYER, null);
        UUID key = UUID.randomUUID();
        verify(DisputeExitType.CONCEDE, first, BUYER, challenge, key);

        verify(DisputeExitType.CONCEDE, first, BUYER, challenge, key);

        assertThat(events("remedy.finalized")).hasSize(1);
        assertThat(events("inspection.level1_5_cancelled")).hasSize(1);
    }

    @Test
    void noExit_afterACompleteReport_norForAMandatoryInspectionCase() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", PARTIALLY));
        assertExitNotAllowed(() -> initiate(DisputeExitType.CONCEDE, first, SELLER, null));
        assertExitNotAllowed(() -> initiate(DisputeExitType.SETTLE, first, SELLER, "100000000.00"));
        assertThat(detailOf(first, BUYER, "BUYER").disputeExit()).isNull();

        activate(true);
        delivered(first, "10000", "9800");
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", PARTIALLY));
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertExitNotAllowed(() -> initiate(DisputeExitType.CONCEDE, first, SELLER, null));
    }

    @Test
    void aMandatoryInspectionContract_refusesTheExit_andKeepsItsInspection() {
        activate(true);
        flagged(first, FlagIssueType.QUANTITY, "10000", "9800");
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);

        assertExitNotAllowed(() -> initiate(DisputeExitType.CONCEDE, first, SELLER, null));
        assertExitNotAllowed(() -> initiate(DisputeExitType.CONCEDE, first, BUYER, null));

        assertThat(detailOf(first, BUYER, "BUYER").disputeExit()).isNull();
        assertThat(jdbc.queryForList("SELECT reason FROM level1_5_request WHERE milestone_id = ? AND ended_at IS NULL",
                String.class, first.milestoneId())).containsExactly("MANDATORY_INSPECTION");
        assertThat(events("inspection.level1_5_cancelled")).isEmpty();
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
    }

    // ---- settlement ----

    @Test
    void settlement_rangeAndProposalConflicts_thenAcceptEqual_noFault_noDisputeResolved() throws Exception {
        activate(false);
        contested(first, FlagIssueType.QUANTITY, "9000", "8000");

        assertThatThrownBy(() -> initiate(DisputeExitType.SETTLE, first, BUYER, "119999999.00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> initiate(DisputeExitType.SETTLE, first, BUYER, "135000001.00"))
                .isInstanceOf(IllegalArgumentException.class);

        settle(first, BUYER, "125000000.00");
        assertProposalConflict(() -> initiate(DisputeExitType.SETTLE, first, BUYER, "125000000.00"));
        assertProposalConflict(() -> initiate(DisputeExitType.SETTLE, first, SELLER, "130000000.00"));

        settle(first, SELLER, "125000000.00");

        Milestone settled = reload(first);
        assertThat(settled.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settled.settlement().sellerAmount().wire()).isEqualTo("125000000.00");
        assertThat(settled.settlement().buyerRefundAmount().wire()).isEqualTo("25000000.00");
        String caseId = settled.flag().breachCaseId();
        assertThat(decisionOf(caseId)).containsEntry("final_breaching_role", null)
                .containsEntry("decision_source", "MUTUAL").containsEntry("status", "FINALIZED");
        assertThat(events("milestone.dispute_resolved")).isEmpty();
        FrozenSchemas.assertEvent("remedyFinalized", events("remedy.finalized").get(0));
        assertThat(events("inspection.level1_5_cancelled").get(0).at("/payload/reason").asText())
                .isEqualTo("DISPUTE_SETTLED");
        assertThat(jdbc.queryForList("SELECT status FROM dispute_settlement_proposal WHERE milestone_id = ?",
                String.class, first.milestoneId())).containsExactly("ACCEPTED");
        assertThat(Fakes.sent).extracting(OtpEmail::purpose).containsOnly(OtpEmail.SETTLE_DISPUTE);
    }

    @Test
    void deleteProposal_byProposerWithdraws_byOtherRefuses_noneIs409() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");

        settle(first, BUYER, "100000000.00");
        exits.withdraw(new DisputeExitCommands.Withdraw(id(), first.milestoneId(), BUYER, UUID.randomUUID()));
        settle(first, BUYER, "90000000.00");
        exits.withdraw(new DisputeExitCommands.Withdraw(id(), first.milestoneId(), SELLER, UUID.randomUUID()));

        assertThat(jdbc.queryForList("SELECT status FROM dispute_settlement_proposal WHERE milestone_id = ?"
                + " ORDER BY proposed_at", String.class, first.milestoneId())).containsExactly("WITHDRAWN", "REFUSED");
        assertProposalConflict(() -> exits.withdraw(new DisputeExitCommands.Withdraw(id(), first.milestoneId(),
                SELLER, UUID.randomUUID())));
        assertThat(reload(first).status()).isEqualTo(MilestoneStatus.CONTESTED);
    }

    // ---- inspection fee ----

    @Test
    void inconclusiveFirstReport_settlementSplitsTheFee_concessionPutsItOnTheConceder() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        deliver(reportPayload(first.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));
        settle(first, BUYER, "100000000.00");
        settle(first, SELLER, "100000000.00");
        assertThat(decisionOf(reload(first).flag().breachCaseId())).containsEntry("inspection_fee_party", "SPLIT");

        contested(second, FlagIssueType.QUALITY, "10000", "9800");
        deliver(reportPayload(second.milestoneId(), "INCONCLUSIVE", "0.000", "0.000", CONFORMING));
        concede(second, SELLER);
        assertThat(decisionOf(reload(second).flag().breachCaseId())).containsEntry("inspection_fee_party", "SELLER");
    }

    @Test
    void fee_insideTheFreeWindowNone_afterItTheConcederOrTheRequester() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        backdateRequest(first);
        assign(first, Instant.now().minusSeconds(60), Instant.now().plusSeconds(4 * 3600));
        concede(first, BUYER);
        assertThat(decisionOf(reload(first).flag().breachCaseId())).containsEntry("inspection_fee_party", null);

        contested(second, FlagIssueType.QUALITY, "10000", "9800");
        backdateRequest(second);
        assign(second, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(60));
        concede(second, BUYER);
        assertThat(decisionOf(reload(second).flag().breachCaseId())).containsEntry("inspection_fee_party", "BUYER");
    }

    @Test
    void fee_ofASettlementAfterTheFreeWindow_isOnThePartyThatRequestedTheInspection() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        backdateRequest(first);
        assign(first, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(60));

        settle(first, BUYER, "100000000.00");
        settle(first, SELLER, "100000000.00");

        assertThat(decisionOf(reload(first).flag().breachCaseId())).containsEntry("inspection_fee_party", "SELLER");
    }

    // ---- a contested decision ----

    @Test
    void aContestedDecision_thenTheBuyerConcedes_oldDecisionSuperseded_newOneFinalizedWithNoFault() throws Exception {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        String caseId = reload(first).flag().breachCaseId();
        deliver(reportPayload(first.milestoneId(), "COMPLETE", "9800.000", "9800.000", NON_CONFORMING));
        reviewCase.execute(new BreachCaseCommands.Review(id(), caseId, ARBITRATOR, UUID.randomUUID()));
        resolveCase.execute(new BreachCaseCommands.Resolve(id(), caseId, ARBITRATOR, UUID.randomUUID(),
                SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5, List.of("r")));
        contestUc.contest(new BreachCaseCommands.Contest(id(), caseId, SELLER, UUID.randomUUID(), "not my sample",
                List.of()));
        assertThat(detailOf(first, BUYER, "BUYER").disputeExit().buyerPositionAmount()).isEqualTo("0.00");

        concede(first, BUYER);

        List<Map<String, Object>> decisions = jdbc.queryForList("SELECT * FROM remedy_decision WHERE"
                + " breach_case_id = ? ORDER BY `round`", caseId);
        assertThat(decisions).extracting(d -> d.get("status"), d -> d.get("decision_source"),
                d -> d.get("final_breaching_role")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("SUPERSEDED", "INSPECTOR_L1_5", "SELLER"),
                org.assertj.core.groups.Tuple.tuple("FINALIZED", "MUTUAL", null));
        assertThat(reload(first).settlement().sellerAmount().wire()).isEqualTo("150000000.00");
        assertThat(events("remedy.finalized")).hasSize(1);
        assertThat(events("milestone.dispute_resolved").get(0).at("/payload/resolutionFavors").asText())
                .isEqualTo("SELLER");
        assertThat(events("inspection.level1_5_cancelled")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(l.amount), 0) FROM remedy_leg l JOIN remedy_decision d"
                + " ON d.remedy_decision_id = l.remedy_decision_id WHERE d.contract_id = ? AND d.status <> 'SUPERSEDED'"
                + " AND l.fund_type = 'SELLER_DEPOSIT'", BigDecimal.class, id())).isEqualByComparingTo("0");
    }

    // ---- inspection.level1_5_assigned ----

    @Test
    void assignedConsumer_dedupsByEventId_andRefusesAMalformedPayload() {
        activate(false);
        contested(first, FlagIssueType.QUALITY, "10000", "9800");
        Map<String, Object> payload = assignedPayload(first, Instant.now(), Instant.now().plusSeconds(3600));
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_assigned", 1,
                Instant.now(), "inspection-service", first.milestoneId(), null, null, null, payload);

        assignedConsumer.onAssigned(message(envelope));
        assignedConsumer.onAssigned(message(envelope));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_assignment WHERE milestone_id = ?",
                Integer.class, first.milestoneId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class,
                envelope.eventId().toString())).isEqualTo(1);

        Map<String, Object> broken = new LinkedHashMap<>(payload);
        broken.remove("freeCancelUntil");
        assertThatThrownBy(() -> assignedConsumer.onAssigned(message(new EventEnvelope(UUID.randomUUID(),
                "inspection.level1_5_assigned", 1, Instant.now(), "inspection-service", first.milestoneId(), null,
                null, null, broken)))).isInstanceOf(InvalidEventPayloadException.class);
    }

    // ---- flows ----

    private void concede(Milestone milestone, String actor) {
        SignChallengeView challenge = initiate(DisputeExitType.CONCEDE, milestone, actor, null);
        verify(DisputeExitType.CONCEDE, milestone, actor, challenge, UUID.randomUUID());
    }

    private void settle(Milestone milestone, String actor, String amount) {
        SignChallengeView challenge = initiate(DisputeExitType.SETTLE, milestone, actor, amount);
        verify(DisputeExitType.SETTLE, milestone, actor, challenge, UUID.randomUUID());
    }

    private SignChallengeView initiate(DisputeExitType type, Milestone milestone, String actor, String amount) {
        return exits.initiate(type, new DisputeExitCommands.Initiate(id(), milestone.milestoneId(), actor,
                UUID.randomUUID(), amount == null ? null : VndAmount.parse(amount), "1.0", signedHash(),
                Instant.now()));
    }

    private void verify(DisputeExitType type, Milestone milestone, String actor, SignChallengeView challenge,
                        UUID key) {
        String code = Fakes.sent.stream().filter(e -> e.requestId().equals(challenge.otpId().toString()))
                .findFirst().orElseThrow().otpCode();
        exits.verify(type, new DisputeExitCommands.Verify(id(), milestone.milestoneId(), actor, key,
                challenge.otpId(), code, Instant.now()));
    }

    private String signedHash() {
        return contracts.findById(contract.getContractId()).orElseThrow().getCoffeeTerms().signedContentHash();
    }

    private void flagged(Milestone milestone, FlagIssueType type, String sellerKg, String buyerKg) {
        delivered(milestone, sellerKg, buyerKg);
        flag.execute(new MilestoneEvidenceCommands.Flag(id(), milestone.milestoneId(), BUYER, UUID.randomUUID(), type,
                "1.1", flagHash(milestone, type)));
    }

    private void contested(Milestone milestone, FlagIssueType type, String sellerKg, String buyerKg) {
        flagged(milestone, type, sellerKg, buyerKg);
        respond.execute(new MilestoneEvidenceCommands.Respond(id(), milestone.milestoneId(), SELLER,
                UUID.randomUUID(), "1.0", ContestFlagConsent.contentHash(reload(milestone))));
    }

    private void assign(Milestone milestone, Instant assignedAt, Instant freeCancelUntil) {
        assignedConsumer.onAssigned(message(new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_assigned", 1,
                Instant.now(), "inspection-service", milestone.milestoneId(), null, null, null,
                assignedPayload(milestone, assignedAt, freeCancelUntil))));
    }

    private Map<String, Object> assignedPayload(Milestone milestone, Instant assignedAt, Instant freeCancelUntil) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", id());
        payload.put("milestoneId", milestone.milestoneId());
        payload.put("commissionId", UUID.randomUUID().toString());
        payload.put("assignedAt", assignedAt.toString());
        payload.put("freeCancelUntil", freeCancelUntil.toString());
        payload.put("reportDueAt", assignedAt.plusSeconds(5 * 86400).toString());
        return payload;
    }

    private void backdateRequest(Milestone milestone) {
        jdbc.update("UPDATE level1_5_request SET requested_at = ? WHERE milestone_id = ? AND ended_at IS NULL",
                Timestamp.from(Instant.now().minusSeconds(3 * 3600)), milestone.milestoneId());
    }

    private int openProposals(Milestone milestone) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dispute_settlement_proposal WHERE milestone_id = ?"
                + " AND status = 'OPEN'", Integer.class, milestone.milestoneId());
    }

    private MilestoneDetailResponse detailOf(Milestone milestone, String actor, String role) {
        return MilestoneDetailResponse.from(detail.execute(id(), milestone.milestoneId(), actor, Set.of(role)));
    }

    private Map<String, Object> decisionOf(String caseId) {
        return jdbc.queryForMap("SELECT * FROM remedy_decision WHERE breach_case_id = ? ORDER BY `round` DESC LIMIT 1",
                caseId);
    }

    private static void assertExitNotAllowed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.DISPUTE_EXIT_NOT_ALLOWED));
    }

    private static void assertProposalConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.SETTLEMENT_PROPOSAL_CONFLICT));
    }

    // ---- fixture (as RemedyFinalizedIntegrationTest) ----

    private void activate(boolean mandatory) {
        contract = offered(terms(mandatory));
        jdbc.update("UPDATE contracts SET status = 'SETTLED' WHERE status = 'ACTIVE'");
        jdbc.update("UPDATE contracts SET status = 'ACTIVE' WHERE contract_id = ?", id());
        Contract active = contracts.findById(contract.getContractId()).orElseThrow();
        List<Milestone> planned = transactions.inTransaction(
                () -> funding.planOnActivation(active, Instant.now(), id(), null));
        first = planned.get(0);
        second = planned.get(1);
        Timestamp now = Timestamp.from(Instant.now());
        for (Milestone m : planned) {
            jdbc.update("UPDATE milestone SET funding_status = 'LOCKED', effective_unit_price = 15000.00,"
                    + " batch_amount = 150000000, funding_requested_at = ?, funding_locked_at = ?,"
                    + " status = 'IN_PROGRESS' WHERE milestone_id = ?", now, now, m.milestoneId());
        }
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
        return new com.agricontract.contract.domain.model.terms.ContractTermsInput(VndAmount.parse("15000.00"),
                t.currency(), List.of(new com.agricontract.contract.domain.model.terms.MilestoneTerm(1,
                                new BigDecimal("10000"), java.time.LocalDate.of(2036, 12, 15), 2),
                        new com.agricontract.contract.domain.model.terms.MilestoneTerm(2, new BigDecimal("10000"),
                                java.time.LocalDate.of(2037, 1, 20), 2)),
                quality, t.deliveryTerms(), t.inspectionTerms(),
                new com.agricontract.contract.domain.model.terms.PriceBand(VndAmount.parse("14000.00"),
                        VndAmount.parse("16000.00")),
                t.priceAdjustmentRule(), t.toleranceRate(), t.shortfallPenaltyThreshold(), t.buyerPenaltyRate(),
                new BigDecimal("0.08"), t.forceMajeureReportWindowDays(), t.buyerConfirmWindowDays(),
                t.buyerDepositRate(), t.disputeFloorReleaseRate(), new BigDecimal("0.075"), t.level2InspectorOrg(),
                t.legalProfile(), t.fundingLeadBusinessDays());
    }

    private static com.agricontract.contract.domain.model.terms.NumericDeviationPolicy max(String tol, String pen,
                                                                                         String rej, String rate) {
        return new com.agricontract.contract.domain.model.terms.NumericDeviationPolicy(
                com.agricontract.contract.domain.model.terms.DeviationDirection.MAXIMUM, new BigDecimal(tol),
                new BigDecimal(pen), new BigDecimal(rej), new BigDecimal(rate));
    }

    private void delivered(Milestone milestone, String sellerKg, String buyerKg) {
        String sellerPhoto = uploadReady(SELLER, milestone);
        BigDecimal s = new BigDecimal(sellerKg);
        weigh.execute(new MilestoneEvidenceCommands.Weigh(id(), milestone.milestoneId(), SELLER, UUID.randomUUID(), s,
                sellerPhoto, null, "1.0", SellerWeighConsent.contentHash(milestone.milestoneId(), s, sellerPhoto,
                null)));
        String buyerPhoto = uploadReady(BUYER, milestone);
        BigDecimal b = new BigDecimal(buyerKg);
        receive.execute(new MilestoneEvidenceCommands.Receive(id(), milestone.milestoneId(), BUYER, UUID.randomUUID(),
                b, buyerPhoto, null, "1.0", BuyerReceiptConsent.contentHash(milestone.milestoneId(), b, buyerPhoto,
                null)));
    }

    private String flagHash(Milestone milestone, FlagIssueType type) {
        Milestone current = reload(milestone);
        return FlagMilestoneConsent.contentHash(current.milestoneId(), current.sellerWeighing().declarationCount(),
                current.sellerWeighing().declaredWeight(), current.buyerReceipt().receivedWeight(), type);
    }

    private String uploadReady(String actor, Milestone milestone) {
        byte[] bytes = "jpeg".getBytes(StandardCharsets.UTF_8);
        String fileId = upload.execute(new MilestoneEvidenceCommands.Upload(id(), milestone.milestoneId(), actor,
                UUID.randomUUID(), "image/jpeg", bytes.length), new ByteArrayInputStream(bytes)).fileId();
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("fileId", fileId);
        ready.put("uploadedBy", actor);
        ready.put("ingestChannel", "DIRECT_UPLOAD");
        ready.put("contentType", "image/jpeg");
        ready.put("fileSize", 4);
        scans.onReady(message(new EventEnvelope(UUID.randomUUID(), "file.ready", 1, Instant.now(), "file-service",
                fileId, null, null, null, ready)));
        return fileId;
    }

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

    private void deliver(Map<String, Object> payload) {
        reportConsumer.onReportConfirmed(message(new EventEnvelope(UUID.randomUUID(), "inspection.report_confirmed", 1,
                Instant.now(), "inspection-service", payload.get("milestoneId").toString(), null, null, null,
                payload)));
    }

    private List<JsonNode> events(String type) {
        return jdbc.queryForList("SELECT envelope FROM event_outbox WHERE event_type = ? ORDER BY id", String.class,
                type).stream().map(json -> {
                    try {
                        return new ObjectMapper().readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).filter(e -> id().equals(e.at("/payload/contractId").asText())).toList();
    }

    private static Message message(EventEnvelope envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(new EventEnvelopeCodec().toJson(envelope).getBytes(), properties);
    }

    private Milestone reload(Milestone milestone) {
        return milestones.findById(milestone.milestoneId()).orElseThrow();
    }

    private String id() {
        return contract.getContractId().value();
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
