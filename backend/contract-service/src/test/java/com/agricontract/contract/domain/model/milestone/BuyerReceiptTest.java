package com.agricontract.contract.domain.model.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.exception.ContractConflictException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D76 step 1: the buyer's receipt, its closing of the seller's correction window, and the RECORD_BUYER_RECEIPT consent. */
class BuyerReceiptTest {
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Instant AT = Instant.parse("2036-12-10T02:00:00Z");
    private static final String MILESTONE = "6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final String SELLER_PHOTO = "11111111-2222-4333-8444-555555555555";
    private static final String PHOTO = "aaaaaaaa-2222-4333-8444-555555555555";
    private static final String CERT = "66666666-7777-4888-8999-000000000000";
    private static final OperationalConsentStatements STATEMENTS = new OperationalConsentStatements(Map.of(
            ConsentActionType.RECORD_BUYER_RECEIPT, new ConsentStatement("RECORD_BUYER_RECEIPT_AS_BUYER", "1.0",
                    "Nhận [X] kg.", CanonicalTerms.sha256("Nhận [X] kg."))));

    private static Milestone inProgress() {
        Milestone m = Milestone.plan(MILESTONE, "c-1", new MilestoneTerm(1, new BigDecimal("10000"),
                LocalDate.parse("2036-12-15"), 2), LocalDate.parse("2036-12-04"));
        m.requestFunding(VndAmount.parse("95000.00"), AT.minusSeconds(86_400 * 7));
        m.recordFunded(AT.minusSeconds(86_400 * 6), 0);
        return m;
    }

    private static Milestone weighed() {
        Milestone m = inProgress();
        m.declareSellerWeight(new BigDecimal("9500"), SELLER_PHOTO, null, AT, WINDOW);
        return m;
    }

    @Test
    void theReceipt_movesSellerWeighedToBuyerReceived_andKeepsEverythingRecorded() {
        Milestone m = weighed();

        m.recordBuyerReceipt(new BigDecimal("9400.250"), PHOTO, CERT, AT.plusSeconds(600));

        assertThat(m.status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(m.buyerReceipt()).isEqualTo(new BuyerReceipt(new BigDecimal("9400.250"), PHOTO, CERT,
                AT.plusSeconds(600)));
        assertThat(m.sellerWeighing().declaredWeight()).isEqualByComparingTo("9500");
    }

    @Test
    void theBuyersWeight_mayBeAboveOrBelowTheSellers() {
        Milestone below = weighed();
        below.recordBuyerReceipt(new BigDecimal("1"), PHOTO, null, AT.plusSeconds(1));
        Milestone above = weighed();
        above.recordBuyerReceipt(new BigDecimal("1000000"), PHOTO, null, AT.plusSeconds(1));

        assertThat(below.status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
        assertThat(above.status()).isEqualTo(MilestoneStatus.BUYER_RECEIVED);
    }

    @Test
    void anyOtherState_isNotAllowed_aSecondReceiptIncluded() {
        Milestone notWeighed = inProgress();
        assertConflict(() -> notWeighed.recordBuyerReceipt(new BigDecimal("9400"), PHOTO, null, AT),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(notWeighed.status()).isEqualTo(MilestoneStatus.IN_PROGRESS);
        assertThat(notWeighed.buyerReceipt()).isNull();

        Milestone received = weighed();
        received.recordBuyerReceipt(new BigDecimal("9400"), PHOTO, null, AT.plusSeconds(60));
        assertConflict(() -> received.recordBuyerReceipt(new BigDecimal("9300"), CERT, null, AT.plusSeconds(120)),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
        assertThat(received.buyerReceipt().receivedWeight()).isEqualByComparingTo("9400");
    }

    @Test
    void anInvalidWeight_isRefused_andNothingChanges() {
        for (String bad : new String[] {"0", "-1", "1000000.001", "1.0001"}) {
            Milestone m = weighed();
            assertThatThrownBy(() -> m.recordBuyerReceipt(new BigDecimal(bad), PHOTO, null, AT))
                    .isInstanceOf(RuntimeException.class);
            assertThat(m.status()).isEqualTo(MilestoneStatus.SELLER_WEIGHED);
            assertThat(m.buyerReceipt()).isNull();
        }
    }

    @Test
    void theSellerCannotCorrect_afterReceipt_evenInsideTheWindow() {
        Milestone m = weighed();
        assertThat(m.isCorrectable(AT.plusSeconds(120), WINDOW)).isTrue();

        m.recordBuyerReceipt(new BigDecimal("9400"), PHOTO, null, AT.plusSeconds(60));

        assertThat(m.isCorrectable(AT.plusSeconds(120), WINDOW)).isFalse();
        assertConflict(() -> m.declareSellerWeight(new BigDecimal("9450"), SELLER_PHOTO, null, AT.plusSeconds(120),
                WINDOW), ContractConflictException.WEIGH_CORRECTION_CLOSED);
        assertConflict(() -> m.requireEvidenceUploadTurn(SignerRole.SELLER, AT.plusSeconds(120), WINDOW),
                ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED);
    }

    @Test
    void theContentHash_isThePipeString_withThreeDecimalKg_lowercaseIds_andEmptyForNull() {
        String milestone = "11111111-1111-1111-1111-111111111111";
        String file = "22222222-2222-2222-2222-222222222222";
        assertThat(BuyerReceiptConsent.contentHash(milestone, new BigDecimal("9700"), file, null))
                .as("fixed vector: printf '%s' 'RECORD_BUYER_RECEIPT|1111...|9700.000|2222...|' | sha256sum")
                .isEqualTo("db7d05c05819c511b174effbb6e90f992d1975f5f8e1830ebbb08c9b80416682");
        assertThat(BuyerReceiptConsent.contentHash(MILESTONE.toUpperCase(), new BigDecimal("9400.5"), PHOTO, CERT))
                .isEqualTo(CanonicalTerms.sha256("RECORD_BUYER_RECEIPT|" + MILESTONE + "|9400.500|" + PHOTO + "|"
                        + CERT));
    }

    @Test
    void theConsent_isTheFixedStatement_andBindsExactlyThisReceipt() {
        Milestone m = weighed();
        String hash = BuyerReceiptConsent.contentHash(MILESTONE, new BigDecimal("9400.50"), PHOTO, null);
        UUID consentId = UUID.randomUUID();

        ConsentEvidence e = BuyerReceiptConsent.record(m, "buyer", new BigDecimal("9400.50"), PHOTO, null, "1.0",
                hash, STATEMENTS, consentId, AT, "key");

        assertThat(e.actionType()).isEqualTo(ConsentActionType.RECORD_BUYER_RECEIPT);
        assertThat(e.subjectType()).isEqualTo(ConsentSubjectType.MILESTONE);
        assertThat(e.subjectId()).isEqualTo(MILESTONE);
        assertThat(e.actorRole()).isEqualTo(SignerRole.BUYER);
        assertThat(e.presentedContentHash()).isEqualTo(hash);

        assertConflict(() -> BuyerReceiptConsent.record(m, "buyer", new BigDecimal("9400"), PHOTO, null, "1.0", hash,
                STATEMENTS, consentId, AT, "key"), ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertConflict(() -> BuyerReceiptConsent.record(m, "buyer", new BigDecimal("9400.5"), PHOTO, CERT, "1.0",
                hash, STATEMENTS, consentId, AT, "key"), ContractConflictException.CONSENT_CONTENT_MISMATCH);
        assertConflict(() -> BuyerReceiptConsent.record(m, "buyer", new BigDecimal("9400.5"), PHOTO, null, "0.9",
                hash, STATEMENTS, consentId, AT, "key"), ContractConflictException.CONSENT_STATEMENT_OUTDATED);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
