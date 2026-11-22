package com.agricontract.contract.domain.model.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** #134: the two consent hashes, the seller-silence money (D82) and the flag and contest transitions (D80). */
class MilestoneFlagTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final Instant AT = Instant.parse("2036-12-10T03:00:00Z");

    @Test
    void theFlagHash_isTheSha256OfTheLiteralString() {
        // printf %s 'FLAG_MILESTONE_ISSUE|1111...|2|10000.000|9000.000|QUANTITY' | sha256sum
        String expected = "149178c1cbfdcd3e90fce258cc6a09191520a4d44a030ec76e023a72aa267322";
        assertThat(FlagMilestoneConsent.contentHash(ID, 2, new BigDecimal("10000"), new BigDecimal("9000"),
                FlagIssueType.QUANTITY)).isEqualTo(expected);
        assertThat(FlagMilestoneConsent.contentHash(ID.toUpperCase(), 2, new BigDecimal("10000.0"),
                new BigDecimal("9000.000"), FlagIssueType.QUANTITY)).isEqualTo(expected);
        assertThat(FlagMilestoneConsent.contentHash(ID, 2, new BigDecimal("10000"), new BigDecimal("9000"),
                FlagIssueType.QUALITY)).isNotEqualTo(expected);
    }

    @Test
    void theContestHash_isTheSha256OfTheLiteralString() {
        // printf %s 'CONTEST_MILESTONE_FLAG|1111...|10000.000|9000.000|QUANTITY' | sha256sum
        assertThat(ContestFlagConsent.contentHash(ID, new BigDecimal("10000"), new BigDecimal("9000"),
                FlagIssueType.QUANTITY))
                .isEqualTo("f7953a91fe1c8fd1a14cb4e963152d1692ec6f62f7528730a2a315b00be49ab1");
    }

    @ParameterizedTest
    @CsvSource({
            "9000,      50000.00, 500000000.00, 450000000, 50000000",
            "10500,     50000.00, 500000000.00, 500000000, 0",
            "9000.001,  33333.33, 333333300.00, 300000003, 33333297",
            "9000.015,  33333.33, 333333300.00, 300000470, 33332830"})
    void sellerSilence_settlesOnTheBuyersWeight_withNoTolerance(String b, String p, String l, String seller,
                                                                  String refund) {
        CleanSettlement.Result result = SellerSilenceSettlement.compute(new BigDecimal(b), VndAmount.parse(p),
                VndAmount.parse(l));

        assertThat(result.sellerAmount().value()).isEqualByComparingTo(seller);
        assertThat(result.buyerRefund().value()).isEqualByComparingTo(refund);
        assertThat(result.sellerAmount().value().add(result.buyerRefund().value())).isEqualByComparingTo(l);
        assertThat(result.billableKg()).isEqualByComparingTo(b);
    }

    @Test
    void aFlag_isOnlyFromBuyerReceived_andOnlyOnce() {
        for (MilestoneStatus status : MilestoneStatus.values()) {
            if (status == MilestoneStatus.BUYER_RECEIVED) {
                continue;
            }
            Milestone m = milestone(status, null);
            assertRefused(() -> m.flagIssue(FlagIssueType.QUANTITY, AT));
        }
        Milestone m = milestone(MilestoneStatus.BUYER_RECEIVED, null);
        m.flagIssue(FlagIssueType.QUALITY, AT);

        assertThat(m.status()).isEqualTo(MilestoneStatus.AWAITING_SELLER_RESPONSE);
        assertThat(m.flag().issueType()).isEqualTo(FlagIssueType.QUALITY);
        assertThat(m.flag().flaggedAt()).isEqualTo(AT);
        assertThat(m.flag().contested()).isFalse();
        assertRefused(() -> m.flagIssue(FlagIssueType.QUALITY, AT));
    }

    @Test
    void aContest_isOnlyFromAwaitingSellerResponse_andOnlyOnce() {
        assertRefused(() -> milestone(MilestoneStatus.BUYER_RECEIVED, null).contest(ContestedBy.SELLER, "bc", AT));
        Milestone m = milestone(MilestoneStatus.AWAITING_SELLER_RESPONSE,
                new MilestoneFlag(FlagIssueType.QUALITY, AT, null, null, null));

        m.contest(ContestedBy.SYSTEM, "bc-1", AT.plusSeconds(60));

        assertThat(m.status()).isEqualTo(MilestoneStatus.CONTESTED);
        assertThat(m.flag().contestedBy()).isEqualTo(ContestedBy.SYSTEM);
        assertThat(m.flag().contestedAt()).isEqualTo(AT.plusSeconds(60));
        assertThat(m.flag().breachCaseId()).isEqualTo("bc-1");
        assertRefused(() -> m.contest(ContestedBy.SELLER, "bc-2", AT));
    }

    @Test
    void theSilentSettlement_isOnlyForAQuantityFlagAwaitingTheSeller_andOnlyOnce() {
        Milestone quality = milestone(MilestoneStatus.AWAITING_SELLER_RESPONSE,
                new MilestoneFlag(FlagIssueType.QUALITY, AT, null, null, null));
        assertRefused(() -> quality.settleSellerSilentQuantity(AT));
        assertRefused(() -> milestone(MilestoneStatus.BUYER_RECEIVED, null).settleSellerSilentQuantity(AT));

        Milestone m = milestone(MilestoneStatus.AWAITING_SELLER_RESPONSE,
                new MilestoneFlag(FlagIssueType.QUANTITY, AT, null, null, null));
        MilestoneSettlement settlement = m.settleSellerSilentQuantity(AT.plusSeconds(1));

        assertThat(m.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(settlement.path()).isEqualTo(SettlementPath.SELLER_SILENT_QUANTITY);
        assertThat(settlement.sellerAmount().value()).isEqualByComparingTo("450000000");
        assertThat(settlement.buyerRefundAmount().value()).isEqualByComparingTo("50000000");
        assertRefused(() -> m.settleSellerSilentQuantity(AT));
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED));
    }

    /** Seller declared 10000 kg, buyer received 9000 kg, 50,000 per kg, 500,000,000 locked. */
    private static Milestone milestone(MilestoneStatus status, MilestoneFlag flag) {
        String photo = "11111111-1111-1111-1111-111111111111";
        return Milestone.reconstitute(ID, "c-1", 1, new BigDecimal("10000"), LocalDate.parse("2036-12-15"), 2,
                LocalDate.parse("2036-12-04"), status, FundingStatus.LOCKED, VndAmount.parse("50000.00"),
                VndAmount.parse("500000000.00"), AT, AT, 0, null,
                new SellerWeighing(new BigDecimal("10000"), photo, null, AT, 1),
                new BuyerReceipt(new BigDecimal("9000"), photo, null, AT), null, flag);
    }
}
