package com.agricontract.contract.domain.model.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** D83, D85: who prevails (and so who does not pay the inspection fee). */
class PrevailingRoleRuleTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static SignerRole role(QualityDisposition disposition, FlagIssueType flag, String accepted, String seller,
                                   String buyer) {
        return PrevailingRoleRule.prevailing(disposition, flag, d(accepted), d(seller), d(buyer));
    }

    @Test
    void partiallyAndNonConforming_alwaysBuyer_whateverTheFlag() {
        for (QualityDisposition q : new QualityDisposition[] {QualityDisposition.PARTIALLY_CONFORMING,
                QualityDisposition.NON_CONFORMING}) {
            for (FlagIssueType flag : new FlagIssueType[] {FlagIssueType.QUALITY, FlagIssueType.QUANTITY, null}) {
                assertThat(role(q, flag, "9800", "10000", "9500")).isEqualTo(SignerRole.BUYER);
            }
        }
    }

    @Test
    void conformingWithQualityFlagOrNoFlag_isSeller() {
        assertThat(role(QualityDisposition.CONFORMING, FlagIssueType.QUALITY, "9800", "10000", "9500"))
                .isEqualTo(SignerRole.SELLER);
        assertThat(role(QualityDisposition.CONFORMING, null, "10500", "10000", "9500")).isEqualTo(SignerRole.SELLER);
    }

    @Test
    void conformingWithQuantityFlag_theNearerWeightWins_aTieGoesToTheSeller() {
        // |9800 - 9500| = 300 versus |9800 - 10000| = 200: the seller is nearer.
        assertThat(role(QualityDisposition.CONFORMING, FlagIssueType.QUANTITY, "9800", "10000", "9500"))
                .isEqualTo(SignerRole.SELLER);
        // |9600 - 9500| = 100 versus |9600 - 10000| = 400: the buyer is nearer.
        assertThat(role(QualityDisposition.CONFORMING, FlagIssueType.QUANTITY, "9600", "10000", "9500"))
                .isEqualTo(SignerRole.BUYER);
        // |9750 - 9500| = 250 versus |9750 - 10000| = 250.
        assertThat(role(QualityDisposition.CONFORMING, FlagIssueType.QUANTITY, "9750", "10000", "9500"))
                .isEqualTo(SignerRole.SELLER);
    }

    @Test
    void finalBreachingRole_isSellerOnlyForNonConforming() {
        assertThat(PrevailingRoleRule.finalBreachingRole(QualityDisposition.NON_CONFORMING))
                .isEqualTo(SignerRole.SELLER);
        assertThat(PrevailingRoleRule.finalBreachingRole(QualityDisposition.PARTIALLY_CONFORMING)).isNull();
        assertThat(PrevailingRoleRule.finalBreachingRole(QualityDisposition.CONFORMING)).isNull();
    }

    @Test
    void inconclusive_hasNoPrevailingRole() {
        assertThatThrownBy(() -> role(QualityDisposition.INCONCLUSIVE, null, "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
