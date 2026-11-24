package com.agricontract.escrow.domain.model;

import com.agricontract.escrow.domain.exception.InvalidRemedyDecisionException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * remedy.finalized legs to bank commands (#249; D86: role is the party that RECEIVES the money of the leg).
 * <pre>
 * PAYMENT_SETTLEMENT   MILESTONE_PAYMENT | SELLER_DEPOSIT        role SELLER  RELEASE_TO_SELLER
 * PAYMENT_REFUND       MILESTONE_PAYMENT | BUYER_DEPOSIT         role BUYER   REFUND_TO_BUYER
 * DEPOSIT_FORFEITURE   BUYER_DEPOSIT -> SELLER, SELLER_DEPOSIT -> BUYER       DEPOSIT_FORFEITURE
 * CONTRACTUAL_PENALTY, DAMAGES_COMPENSATION   MILESTONE_PAYMENT, either role  same entry type
 * NONE                 anything                                  nothing
 * </pre>
 * A zero amount is skipped. Anything outside the table, a repeated remedyLegId, or a MILESTONE_PAYMENT leg without
 * exactly one affected milestone invalidates the WHOLE decision: nothing is sent.
 */
public final class RemedyLegPlanner {

    private static final String BUYER = "BUYER";
    private static final String SELLER = "SELLER";
    private static final String BUYER_DEPOSIT = "BUYER_DEPOSIT";
    private static final String SELLER_DEPOSIT = "SELLER_DEPOSIT";
    private static final String MILESTONE_PAYMENT = SettlementLeg.MILESTONE_PAYMENT;

    private RemedyLegPlanner() {
    }

    public static List<PlannedRemedyLeg> plan(String buyerId, String sellerId, List<String> affectedMilestoneIds,
                                              List<RemedyLegInput> legs) {
        List<PlannedRemedyLeg> planned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean needsMilestone = false;
        for (RemedyLegInput leg : legs) {
            if (!seen.add(leg.remedyLegId())) {
                throw invalid("remedyLegId " + leg.remedyLegId() + " is repeated");
            }
            RemedyType type = enumOf(leg.remedyType());
            if (leg.amount() == null || leg.amount().signum() < 0) {
                throw invalid("leg " + leg.remedyLegId() + " has a negative or missing amount");
            }
            if (type == RemedyType.NONE) {
                continue;
            }
            RemedyEntryType entry = entryType(type, leg.fundType(), leg.role(), leg.remedyLegId());
            boolean milestoneFund = MILESTONE_PAYMENT.equals(leg.fundType());
            needsMilestone |= milestoneFund;
            if (leg.amount().signum() == 0) {
                continue;
            }
            if (milestoneFund && affectedMilestoneIds.size() != 1) {
                break; // reported below with the one message
            }
            planned.add(new PlannedRemedyLeg(leg.remedyLegId(), type, entry, leg.fundType(),
                    BUYER.equals(leg.role()) ? buyerId : sellerId, leg.role(),
                    milestoneFund ? affectedMilestoneIds.get(0) : null, leg.amount()));
        }
        if (needsMilestone && affectedMilestoneIds.size() != 1) {
            throw invalid("MILESTONE_PAYMENT legs need exactly one affected milestone, got "
                    + affectedMilestoneIds.size());
        }
        return planned;
    }

    private static RemedyEntryType entryType(RemedyType type, String fund, String role, String legId) {
        boolean ok;
        RemedyEntryType entry;
        switch (type) {
            case PAYMENT_SETTLEMENT -> {
                entry = RemedyEntryType.RELEASE_TO_SELLER;
                ok = SELLER.equals(role) && (MILESTONE_PAYMENT.equals(fund) || SELLER_DEPOSIT.equals(fund));
            }
            case PAYMENT_REFUND -> {
                entry = RemedyEntryType.REFUND_TO_BUYER;
                ok = BUYER.equals(role) && (MILESTONE_PAYMENT.equals(fund) || BUYER_DEPOSIT.equals(fund));
            }
            case DEPOSIT_FORFEITURE -> {
                entry = RemedyEntryType.DEPOSIT_FORFEITURE;
                ok = (BUYER_DEPOSIT.equals(fund) && SELLER.equals(role))
                        || (SELLER_DEPOSIT.equals(fund) && BUYER.equals(role));
            }
            case CONTRACTUAL_PENALTY, DAMAGES_COMPENSATION -> {
                entry = RemedyEntryType.valueOf(type.name());
                ok = MILESTONE_PAYMENT.equals(fund) && (BUYER.equals(role) || SELLER.equals(role));
            }
            default -> throw invalid("leg " + legId + " has no bank command");
        }
        if (!ok) {
            throw invalid("leg " + legId + ": " + type + " with fundType " + fund + " and role " + role
                    + " is not allowed");
        }
        return entry;
    }

    private static RemedyType enumOf(String value) {
        try {
            return RemedyType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("remedyType is not known: " + value);
        }
    }

    private static InvalidRemedyDecisionException invalid(String reason) {
        return new InvalidRemedyDecisionException(reason);
    }
}
