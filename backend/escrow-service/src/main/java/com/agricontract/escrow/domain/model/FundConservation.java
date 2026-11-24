package com.agricontract.escrow.domain.model;

import com.agricontract.escrow.domain.exception.InvalidRemedyDecisionException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The conservation check of a remedy decision (#249), per pool = (fundType, milestone for MILESTONE_PAYMENT):
 * ledgerRemaining = sum of LOCK_* entries - sum of every other entry; available = ledgerRemaining - what escrow has
 * sent and the bank has not answered. The legs of a pool must not exceed available; a MILESTONE_PAYMENT pool must be
 * moved whole (a final milestone resolution moves the entire batch).
 */
public final class FundConservation {

    private FundConservation() {
    }

    public static void check(List<PlannedRemedyLeg> legs, List<LedgerEntryView> ledger, List<PoolAmount> inFlight) {
        Map<FundPool, BigDecimal> requested = new LinkedHashMap<>();
        for (PlannedRemedyLeg leg : legs) {
            requested.merge(leg.pool(), leg.amount(), BigDecimal::add);
        }
        Map<FundPool, BigDecimal> remaining = remainingByPool(ledger);
        for (Map.Entry<FundPool, BigDecimal> pool : requested.entrySet()) {
            BigDecimal available = remaining.getOrDefault(pool.getKey(), BigDecimal.ZERO);
            for (PoolAmount sent : inFlight) {
                if (sent.pool().equals(pool.getKey())) {
                    available = available.subtract(sent.amount());
                }
            }
            String name = pool.getKey().fundType()
                    + (pool.getKey().milestoneId() == null ? "" : "/" + pool.getKey().milestoneId());
            int cmp = pool.getValue().compareTo(available);
            if (cmp > 0) {
                throw new InvalidRemedyDecisionException("pool " + name + ": legs " + pool.getValue().toPlainString()
                        + " exceed available " + available.toPlainString());
            }
            if (cmp < 0 && SettlementLeg.MILESTONE_PAYMENT.equals(pool.getKey().fundType())) {
                throw new InvalidRemedyDecisionException("pool " + name + ": legs " + pool.getValue().toPlainString()
                        + " do not move the whole available " + available.toPlainString());
            }
        }
    }

    /**
     * ledgerRemaining of every pool the ledger names: sum of LOCK_* entries minus every other entry. A pool whose
     * money is all moved out is 0.00; a negative pool means the ledger moved more than it locked.
     */
    public static Map<FundPool, BigDecimal> remainingByPool(List<LedgerEntryView> ledger) {
        Map<FundPool, BigDecimal> remaining = new LinkedHashMap<>();
        for (LedgerEntryView entry : ledger) {
            remaining.merge(entry.pool(), entry.signedAmount(), BigDecimal::add);
        }
        return remaining;
    }
}
