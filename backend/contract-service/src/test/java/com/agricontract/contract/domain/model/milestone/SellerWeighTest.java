package com.agricontract.contract.domain.model.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.MilestoneRequestInvalidException;
import com.agricontract.contract.domain.model.consent.ConsentActionType;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.ConsentStatement;
import com.agricontract.contract.domain.model.consent.ConsentSubjectType;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.terms.MilestoneTerm;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D71-D75: the seller's declaration, its correction window, the evidence rules and the DECLARE_SELLER_WEIGHT consent. */
class SellerWeighTest {
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Instant AT = Instant.parse("2036-12-10T02:00:00Z");
    private static final String MILESTONE = "6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final String PHOTO = "11111111-2222-4333-8444-555555555555";
    private static final String CERT = "66666666-7777-4888-8999-000000000000";
    private static final OperationalConsentStatements STATEMENTS = new OperationalConsentStatements(Map.of(
            ConsentActionType.DECLARE_SELLER_WEIGHT, new ConsentStatement("DECLARE_SELLER_WEIGHT_AS_SELLER", "1.0",
                    "Cân [X] kg.", CanonicalTerms.sha256("Cân [X] kg."))));

    private static Milestone inProgress() {
        Milestone m = Milestone.plan(MILESTONE, "c-1", new MilestoneTerm(1, new BigDecimal("10000"),
                LocalDate.parse("2036-12-15"), 2), LocalDate.parse("2036-12-04"));
        m.requestFunding(VndAmount.parse("95000.00"), AT.minusSeconds(86_400 * 7));
        m.recordFunded(AT.minusSeconds(86_400 * 6), 0);
        return m;
    }

    @Test
    void theFirstDeclaration_makesTheMilestoneSellerWeighed_evenAboveTheCommittedQuantity() {
        Milestone m = inProgress();

        int number = m.declareSellerWeight(new BigDecimal("10250.125"), PHOTO, CERT, AT, WINDOW);

        assertThat(number).isEqualTo(1);
        assertThat(m.status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);
        assertThat(m.sellerWeighing()).isEqualTo(new SellerWeighing(new BigDecimal("10250.125"), PHOTO, CERT, AT, 1));
    }

    @Test
    void corrections_countUp_insideTheWindowFromTheFirstDeclaration_only() {
        Milestone m = inProgress();
        m.declareSellerWeight(new BigDecimal("9500"), PHOTO, null, AT, WINDOW);

        assertThat(m.declareSellerWeight(new BigDecimal("9400"), PHOTO, null, AT.plusSeconds(1800), WINDOW))
                .isEqualTo(2);
        assertThat(m.declareSellerWeight(new BigDecimal("9300"), CERT, null, AT.plusSeconds(3599), WINDOW))
                .isEqualTo(3);
        assertThat(m.sellerWeighing().firstDeclaredAt()).isEqualTo(AT);
        assertThat(m.sellerWeighing().declaredWeight()).isEqualByComparingTo("9300");

        assertConflict(() -> m.declareSellerWeight(new BigDecimal("9200"), PHOTO, null, AT.plusSeconds(3600), WINDOW),
                ContractConflictException.WEIGH_CORRECTION_CLOSED);
        assertThat(m.sellerWeighing().declarationCount()).isEqualTo(3);
    }

    @Test
    void onceTheBuyerRecordedReceipt_theDeclarationIsClosed_evenInsideTheWindow() {
        Milestone m = Milestone.reconstitute(MILESTONE, "c-1", 1, new BigDecimal("10000"),
                LocalDate.parse("2036-12-15"), 2, LocalDate.parse("2036-12-04"), MilestoneStatus.BUYER_RECEIVED,
                FundingStatus.LOCKED, VndAmount.parse("95000.00"), VndAmount.parse("950000000.00"), AT, AT, 0, null,
                new SellerWeighing(new BigDecimal("9500"), PHOTO, null, AT, 1),
                new BuyerReceipt(new BigDecimal("9400"), PHOTO, null, AT.plusSeconds(30)));

        assertConflict(() -> m.declareSellerWeight(new BigDecimal("9400"), PHOTO, null, AT.plusSeconds(60), WINDOW),
                ContractConflictException.WEIGH_CORRECTION_CLOSED);
        assertThat(m.isCorrectable(AT.plusSeconds(60), WINDOW)).isFalse();
    }

    @Test
    void anUnfundedMilestone_cannotBeWeighed() {
        Milestone m = Milestone.plan(MILESTONE, "c-1", new MilestoneTerm(1, new BigDecimal("10000"),
                LocalDate.parse("2036-12-15"), 2), LocalDate.parse("2036-12-04"));

        assertConflict(() -> m.declareSellerWeight(new BigDecimal("9500"), PHOTO, null, AT, WINDOW),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(m.status()).isEqualTo(MilestoneStatus.CREATED);
    }

    @Test
    void theWeight_hasTheD72Bounds() {
        for (String bad : new String[] {"0", "-1", "1000000.001", "1.0001"}) {
            assertThatThrownBy(() -> inProgress().declareSellerWeight(new BigDecimal(bad), PHOTO, null, AT, WINDOW))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> inProgress().declareSellerWeight(new BigDecimal("1000000.000"), PHOTO, null, AT, WINDOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> inProgress().declareSellerWeight(new BigDecimal("0.001"), PHOTO, null, AT, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    void uploadTurns_sellerInProgressOrCorrecting_buyerOnceWeighed() {
        Milestone m = inProgress();
        assertThatCode(() -> m.requireEvidenceUploadTurn(SignerRole.SELLER, AT, WINDOW)).doesNotThrowAnyException();
        assertConflict(() -> m.requireEvidenceUploadTurn(SignerRole.BUYER, AT, WINDOW),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);

        m.declareSellerWeight(new BigDecimal("9500"), PHOTO, null, AT, WINDOW);
        assertThatCode(() -> m.requireEvidenceUploadTurn(SignerRole.BUYER, AT.plusSeconds(7200), WINDOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> m.requireEvidenceUploadTurn(SignerRole.SELLER, AT.plusSeconds(60), WINDOW))
                .doesNotThrowAnyException();
        assertConflict(() -> m.requireEvidenceUploadTurn(SignerRole.SELLER, AT.plusSeconds(3600), WINDOW),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
    }

    @Test
    void namedFiles_areTheActorsOwnForThisMilestone_different_thenReady() {
        Map<String, MilestoneEvidenceFile> known = Map.of(
                PHOTO, file(PHOTO, "seller", MILESTONE, EvidenceScanStatus.READY),
                CERT, file(CERT, "seller", MILESTONE, EvidenceScanStatus.PROCESSING),
                "other-milestone", file("other-milestone", "seller", "m-2", EvidenceScanStatus.READY),
                "buyers", file("buyers", "buyer", MILESTONE, EvidenceScanStatus.READY),
                "failed", file("failed", "seller", MILESTONE, EvidenceScanStatus.FAILED));

        assertInvalid(() -> MilestoneEvidenceFile.requireUploadedBy(PHOTO, PHOTO, "seller", MILESTONE, known),
                MilestoneRequestInvalidException.EVIDENCE_FILE_REUSED);
        for (String notMine : new String[] {"other-milestone", "buyers", "unknown"}) {
            assertInvalid(() -> MilestoneEvidenceFile.requireUploadedBy(notMine, null, "seller", MILESTONE, known),
                    MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED);
        }
        assertThatCode(() -> MilestoneEvidenceFile.requireUploadedBy(PHOTO, CERT, "seller", MILESTONE, known))
                .doesNotThrowAnyException();
        assertConflict(() -> MilestoneEvidenceFile.requireReady(PHOTO, CERT, known),
                ContractConflictException.EVIDENCE_NOT_READY);
        assertConflict(() -> MilestoneEvidenceFile.requireReady("failed", null, known),
                ContractConflictException.EVIDENCE_REJECTED);
        assertThatCode(() -> MilestoneEvidenceFile.requireReady(PHOTO, null, known)).doesNotThrowAnyException();
    }

    @Test
    void theContentHash_isTheD84PipeString_withThreeDecimalKg_lowercaseIds_andEmptyForNull() {
        assertThat(SellerWeighConsent.contentHash(MILESTONE, new BigDecimal("9500.5"), PHOTO, CERT))
                .isEqualTo(CanonicalTerms.sha256("DECLARE_SELLER_WEIGHT|" + MILESTONE + "|9500.500|" + PHOTO + "|"
                        + CERT));
        assertThat(SellerWeighConsent.contentHash(MILESTONE.toUpperCase(), new BigDecimal("1000"), PHOTO, null))
                .isEqualTo(CanonicalTerms.sha256("DECLARE_SELLER_WEIGHT|" + MILESTONE + "|1000.000|" + PHOTO + "|"));
        assertThat(SellerWeighConsent.contentHash(MILESTONE, new BigDecimal("9700"), PHOTO, null))
                .as("fixed vector (sha256sum of the string): the FE must produce the same hex")
                .isEqualTo("fb4db1fd007483d3641c63faa8067d63d48b62eb98013c546171c4a33ddc48d3");
    }

    @Test
    void theConsent_isTheFixedStatement_andBindsExactlyThisDeclaration() {
        Milestone m = inProgress();
        String hash = SellerWeighConsent.contentHash(MILESTONE, new BigDecimal("9500.50"), PHOTO, null);
        UUID consentId = UUID.randomUUID();

        ConsentEvidence e = SellerWeighConsent.record(m, "seller", new BigDecimal("9500.50"), PHOTO, null, "1.0",
                hash, STATEMENTS, consentId, AT, "key");

        assertThat(e.actionType()).isEqualTo(ConsentActionType.DECLARE_SELLER_WEIGHT);
        assertThat(e.subjectType()).isEqualTo(ConsentSubjectType.MILESTONE);
        assertThat(e.subjectId()).isEqualTo(MILESTONE);
        assertThat(e.actorRole()).isEqualTo(SignerRole.SELLER);
        assertThat(e.statementHash()).as("hash of the template, placeholder kept (D84)")
                .isEqualTo(CanonicalTerms.sha256("Cân [X] kg."));
        assertThat(e.presentedContentHash()).isEqualTo(hash);

        assertConflict(() -> SellerWeighConsent.record(m, "seller", new BigDecimal("9400"), PHOTO, null, "1.0", hash,
                STATEMENTS, consentId, AT, "key"), ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertConflict(() -> SellerWeighConsent.record(m, "seller", new BigDecimal("9500.5"), PHOTO, CERT, "1.0",
                hash, STATEMENTS, consentId, AT, "key"), ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertConflict(() -> SellerWeighConsent.record(m, "seller", new BigDecimal("9500.5"), PHOTO, null, "0.9",
                hash, STATEMENTS, consentId, AT, "key"), ContractConflictException.CONSENT_STATEMENT_OUTDATED);
    }

    private static MilestoneEvidenceFile file(String id, String uploader, String milestone, EvidenceScanStatus s) {
        return new MilestoneEvidenceFile(id, milestone, "c-1", uploader, SignerRole.SELLER, AT, s);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MilestoneRequestInvalidException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
