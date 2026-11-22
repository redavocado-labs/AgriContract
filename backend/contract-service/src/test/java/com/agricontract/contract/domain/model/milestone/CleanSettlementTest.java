package com.agricontract.contract.domain.model.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** D77 money of a clean milestone, and the BUYER_RECEIVED to SETTLED transition. */
class CleanSettlementTest {
    private static final Instant AT = Instant.parse("2036-12-10T03:00:00Z");

    @ParameterizedTest(name = "S={0} B={1} t={2} P={3} L={4}")
    @CsvSource({
            "10000,    9700,     0.05, 50000.00, 500000000, 10000,    500000000, 0",
            "10000,    9500,     0.05, 50000.00, 500000000, 10000,    500000000, 0",
            "10000,    9000,     0.05, 50000.00, 500000000, 9750,     487500000, 12500000",
            "10000,    9001,     0.05, 50000.00, 500000000, 9750.5,   487525000, 12475000",
            "9500,     9600,     0.05, 50000.00, 500000000, 9500,     475000000, 25000000",
            "10500,    10400,    0.05, 50000.00, 500000000, 10500,    500000000, 0",
            "10000,    9990,     0,    50000.00, 500000000, 9995,     499750000, 250000",
            "1000.001, 1000.001, 0.05, 33333.33, 33366663,  1000.001, 33333363,  33300",
            "10000,    9000,     0.05, 33333.33, 333333300, 9750,     324999968, 8333332"})
    void theD77Vectors(String s, String b, String t, String p, String l, String billable, String seller,
                       String refund) {
        VndAmount locked = VndAmount.ofDong(new BigDecimal(l));

        CleanSettlement.Result r = CleanSettlement.compute(new BigDecimal(s), new BigDecimal(b), new BigDecimal(t),
                VndAmount.parse(p), locked);

        assertThat(r.billableKg()).isEqualByComparingTo(billable);
        assertThat(r.sellerAmount().wire()).isEqualTo(new BigDecimal(seller).setScale(2).toPlainString());
        assertThat(r.buyerRefund().wire()).isEqualTo(new BigDecimal(refund).setScale(2).toPlainString());
        assertThat(r.sellerAmount().value().add(r.buyerRefund().value())).isEqualByComparingTo(locked.value());
    }

    @Test
    void overDelivery_isCappedByTheLockedAmount() {
        CleanSettlement.Result r = CleanSettlement.compute(new BigDecimal("10000"), new BigDecimal("10100"),
                new BigDecimal("0.05"), VndAmount.parse("50000.00"), VndAmount.parse("400000000.00"));

        assertThat(r.billableKg()).isEqualByComparingTo("10000");
        assertThat(r.sellerAmount().wire()).isEqualTo("400000000.00");
        assertThat(r.buyerRefund().wire()).isEqualTo("0.00");
    }

    @Test
    void settleClean_isOnlyFromBuyerReceived_andOnlyOnce() {
        Milestone received = milestone(MilestoneStatus.BUYER_RECEIVED, "9000");

        MilestoneSettlement s = received.settleClean(SettlementPath.CLEAN_AUTO_CONFIRMED, new BigDecimal("0.05"), AT);

        assertThat(received.status()).isEqualTo(MilestoneStatus.SETTLED);
        assertThat(received.settlement()).isSameAs(s);
        assertThat(s.path()).isEqualTo(SettlementPath.CLEAN_AUTO_CONFIRMED);
        assertThat(s.billableQuantityKg()).isEqualByComparingTo("9750");
        assertThat(s.sellerAmount().wire()).isEqualTo("487500000.00");
        assertThat(s.buyerRefundAmount().wire()).isEqualTo("12500000.00");
        assertThat(s.settledAt()).isEqualTo(AT);
        assertRefused(() -> received.settleClean(SettlementPath.CLEAN_CONFIRMED, new BigDecimal("0.05"), AT));
        assertThat(received.settlement()).isSameAs(s);

        for (MilestoneStatus status : new MilestoneStatus[] {MilestoneStatus.IN_PROGRESS,
                MilestoneStatus.SELLER_WEIGHED, MilestoneStatus.CONTESTED}) {
            assertRefused(() -> milestone(status, "9000").settleClean(SettlementPath.CLEAN_CONFIRMED,
                    new BigDecimal("0.05"), AT));
        }
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED));
    }

    /** Seller declared 10000 kg, 50,000 per kg, 500,000,000 locked. */
    private static Milestone milestone(MilestoneStatus status, String buyerKg) {
        String photo = "11111111-1111-1111-1111-111111111111";
        return Milestone.reconstitute("11111111-1111-1111-1111-111111111111", "c-1", 1, new BigDecimal("10000"),
                LocalDate.parse("2036-12-15"), 2, LocalDate.parse("2036-12-04"), status, FundingStatus.LOCKED,
                VndAmount.parse("50000.00"), VndAmount.parse("500000000.00"), AT, AT, 0, null,
                new SellerWeighing(new BigDecimal("10000"), photo, null, AT, 1),
                new BuyerReceipt(new BigDecimal(buyerKg), photo, null, AT), null);
    }
}
