package com.agricontract.contract.domain.model.quality;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Who prevails in an inspected milestone, and so who does not pay the inspection fee (D83, D85, LOSER_PAYS).
 * <pre>
 * PARTIALLY_CONFORMING, NON_CONFORMING : BUYER
 * CONFORMING, QUANTITY flag            : |accepted - buyerReceived| versus |accepted - sellerDeclared|, the smaller
 *                                        wins, a tie goes to SELLER
 * CONFORMING, QUALITY flag or no flag  : SELLER
 * </pre>
 * finalBreachingRole is SELLER only for NON_CONFORMING.
 */
public final class PrevailingRoleRule {
    private PrevailingRoleRule() {
    }

    /** flag is null when the milestone was not flagged (mandatory inspection). The weights are used for QUANTITY only. */
    public static SignerRole prevailing(QualityDisposition disposition, FlagIssueType flag, BigDecimal acceptedKg,
                                        BigDecimal sellerDeclaredKg, BigDecimal buyerReceivedKg) {
        Objects.requireNonNull(disposition, "disposition");
        if (disposition == QualityDisposition.INCONCLUSIVE) {
            throw new IllegalArgumentException("an inconclusive report has no prevailing role");
        }
        if (disposition != QualityDisposition.CONFORMING) {
            return SignerRole.BUYER;
        }
        if (flag != FlagIssueType.QUANTITY) {
            return SignerRole.SELLER;
        }
        Objects.requireNonNull(acceptedKg, "acceptedKg");
        BigDecimal buyerGap = acceptedKg.subtract(buyerReceivedKg).abs();
        BigDecimal sellerGap = acceptedKg.subtract(sellerDeclaredKg).abs();
        return buyerGap.compareTo(sellerGap) < 0 ? SignerRole.BUYER : SignerRole.SELLER;
    }

    /** SELLER only for NON_CONFORMING, else null (no fault). */
    public static SignerRole finalBreachingRole(QualityDisposition disposition) {
        return disposition == QualityDisposition.NON_CONFORMING ? SignerRole.SELLER : null;
    }
}
