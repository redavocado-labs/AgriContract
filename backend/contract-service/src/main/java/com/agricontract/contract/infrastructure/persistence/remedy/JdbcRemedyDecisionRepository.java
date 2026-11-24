package com.agricontract.contract.infrastructure.persistence.remedy;

import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.remedy.InspectionFeeParty;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyFundType;
import com.agricontract.contract.domain.model.remedy.RemedyLeg;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.remedy.RemedyType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** remedy_decision and remedy_leg (V23, V24): insert once, then only the status and its timestamp change. */
@Repository
@RequiredArgsConstructor
public class JdbcRemedyDecisionRepository implements RemedyDecisionRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(RemedyDecision d) {
        jdbc.update("INSERT INTO remedy_decision (remedy_decision_id, contract_id, breach_case_id, milestone_id,"
                        + " status, final_breaching_role, breach_reason_code, quality_disposition, decision_source,"
                        + " penalty_amount, penalty_debt_amount, settled_milestone_count, created_at, finalized_at,"
                        + " `round`, inspection_fee_party) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.remedyDecisionId(), d.contractId(), d.breachCaseId(), d.milestoneId(), d.status().name(),
                name(d.finalBreachingRole()), name(d.breachReasonCode()), name(d.qualityDisposition()),
                d.decisionSource().name(), d.penaltyAmount().value(), d.penaltyDebtAmount().value(),
                d.settledMilestoneCount(), Timestamp.from(d.createdAt()),
                d.finalizedAt() == null ? null : Timestamp.from(d.finalizedAt()), nextRound(d.breachCaseId()),
                name(d.inspectionFeeParty()));
        int seq = 0;
        for (RemedyLeg leg : d.legs()) {
            jdbc.update("INSERT INTO remedy_leg (remedy_leg_id, remedy_decision_id, seq, remedy_type, fund_type,"
                            + " role, amount) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    leg.remedyLegId(), d.remedyDecisionId(), seq++, leg.remedyType().name(), leg.fundType().name(),
                    leg.role().name(), leg.amount().value());
        }
    }

    @Override
    public Optional<RemedyDecision> findById(String remedyDecisionId) {
        return find("SELECT * FROM remedy_decision WHERE remedy_decision_id = ?", remedyDecisionId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RemedyDecision> findByIdForUpdate(String remedyDecisionId) {
        return find("SELECT * FROM remedy_decision WHERE remedy_decision_id = ? FOR UPDATE", remedyDecisionId);
    }

    @Override
    public Optional<RemedyDecision> findByBreachCaseId(String breachCaseId) {
        return find("SELECT * FROM remedy_decision WHERE breach_case_id = ? ORDER BY `round` DESC LIMIT 1",
                breachCaseId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markFinalized(RemedyDecision d) {
        if (d.status() != RemedyStatus.FINALIZED) {
            throw new IllegalArgumentException("Remedy decision " + d.remedyDecisionId() + " is not FINALIZED");
        }
        int updated = jdbc.update("UPDATE remedy_decision SET status = 'FINALIZED', finalized_at = ?"
                + " WHERE remedy_decision_id = ? AND status = 'PENDING_CONTEST'", Timestamp.from(d.finalizedAt()),
                d.remedyDecisionId());
        if (updated != 1) {
            throw new IllegalStateException("Remedy decision " + d.remedyDecisionId() + " is not PENDING_CONTEST");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSuperseded(String remedyDecisionId, Instant at) {
        int updated = jdbc.update("UPDATE remedy_decision SET status = 'SUPERSEDED', superseded_at = ?"
                + " WHERE remedy_decision_id = ? AND status = 'PENDING_CONTEST'", Timestamp.from(at),
                remedyDecisionId);
        if (updated != 1) {
            throw new IllegalStateException("Remedy decision " + remedyDecisionId + " is not PENDING_CONTEST");
        }
    }

    @Override
    public VndAmount depositForfeited(String contractId, RemedyFundType deposit) {
        if (deposit == RemedyFundType.MILESTONE_PAYMENT) {
            throw new IllegalArgumentException("MILESTONE_PAYMENT is not a deposit");
        }
        BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(SUM(l.amount), 0) FROM remedy_leg l JOIN remedy_decision d"
                + " ON d.remedy_decision_id = l.remedy_decision_id WHERE d.contract_id = ? AND l.fund_type = ?"
                + " AND l.remedy_type = 'DEPOSIT_FORFEITURE' AND d.status <> 'SUPERSEDED'", BigDecimal.class,
                contractId, deposit.name());
        return new VndAmount(sum == null ? BigDecimal.ZERO : sum);
    }

    private Optional<RemedyDecision> find(String sql, String id) {
        return jdbc.query(sql, (rs, n) -> {
            String finalRole = rs.getString("final_breaching_role");
            String reason = rs.getString("breach_reason_code");
            String disposition = rs.getString("quality_disposition");
            Timestamp finalizedAt = rs.getTimestamp("finalized_at");
            String decisionId = rs.getString("remedy_decision_id");
            return new RemedyDecision(decisionId, rs.getString("contract_id"), rs.getString("breach_case_id"),
                    rs.getString("milestone_id"), RemedyStatus.valueOf(rs.getString("status")),
                    finalRole == null ? null : SignerRole.valueOf(finalRole),
                    reason == null ? null : BreachReasonCode.valueOf(reason),
                    disposition == null ? null : QualityDisposition.valueOf(disposition),
                    DecisionSource.valueOf(rs.getString("decision_source")),
                    new VndAmount(rs.getBigDecimal("penalty_amount")),
                    new VndAmount(rs.getBigDecimal("penalty_debt_amount")), rs.getInt("settled_milestone_count"),
                    legs(decisionId), rs.getTimestamp("created_at").toInstant(),
                    finalizedAt == null ? null : finalizedAt.toInstant(),
                    rs.getString("inspection_fee_party") == null ? null
                            : InspectionFeeParty.valueOf(rs.getString("inspection_fee_party")));
        }, id).stream().findFirst();
    }

    private List<RemedyLeg> legs(String decisionId) {
        return jdbc.query("SELECT * FROM remedy_leg WHERE remedy_decision_id = ? ORDER BY seq",
                (rs, n) -> new RemedyLeg(rs.getString("remedy_leg_id"), RemedyType.valueOf(rs.getString("remedy_type")),
                        RemedyFundType.valueOf(rs.getString("fund_type")), SignerRole.valueOf(rs.getString("role")),
                        new VndAmount(rs.getBigDecimal("amount"))), decisionId);
    }

    /** 1 for the first decision of a case, 2 for the one that superseded it (V24); 1 when there is no case. */
    private int nextRound(String breachCaseId) {
        if (breachCaseId == null) {
            return 1;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM remedy_decision WHERE breach_case_id = ?",
                Integer.class, breachCaseId);
        return (count == null ? 0 : count) + 1;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
