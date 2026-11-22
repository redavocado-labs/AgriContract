package com.agricontract.escrow.infrastructure.persistence;

import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.RemedyDecision;
import com.agricontract.escrow.domain.model.RemedyDecisionStatus;
import com.agricontract.escrow.domain.model.RemedyEntryType;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.escrow.domain.model.RemedyLegStatus;
import com.agricontract.escrow.domain.model.RemedyType;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRemedyLegRepository implements RemedyLegRepository {

    private static final String LEG_COLUMNS = """
            remedy_leg_id, remedy_decision_id, contract_id, milestone_id, remedy_type, entry_type, fund_type, user_id,
            amount, request_event_id, status, attempts, next_attempt_at, failure_reason, ledger_entry_id, created_at,
            updated_at
            """;

    private static final RowMapper<RemedyLeg> LEG = (rs, rowNum) -> RemedyLeg.reconstitute(
            rs.getString("remedy_leg_id"),
            rs.getString("remedy_decision_id"),
            rs.getString("contract_id"),
            rs.getString("milestone_id"),
            RemedyType.valueOf(rs.getString("remedy_type")),
            RemedyEntryType.valueOf(rs.getString("entry_type")),
            rs.getString("fund_type"),
            rs.getString("user_id"),
            new Money(rs.getBigDecimal("amount")),
            rs.getString("request_event_id"),
            RemedyLegStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            instant(rs.getTimestamp("next_attempt_at")),
            rs.getString("failure_reason"),
            rs.getString("ledger_entry_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<RemedyDecision> DECISION = (rs, rowNum) -> new RemedyDecision(
            rs.getString("remedy_decision_id"),
            rs.getString("contract_id"),
            rs.getString("event_id"),
            RemedyDecisionStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcRemedyLegRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RemedyDecision> findDecisionForUpdate(String remedyDecisionId) {
        return jdbc.query("SELECT remedy_decision_id, contract_id, event_id, status, reject_reason, created_at"
                + " FROM remedy_decision WHERE remedy_decision_id = ? FOR UPDATE", DECISION, remedyDecisionId)
                .stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertDecision(RemedyDecision decision) {
        jdbc.update("INSERT INTO remedy_decision (remedy_decision_id, contract_id, event_id, status, reject_reason,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                decision.remedyDecisionId(), decision.contractId(), decision.eventId(), decision.status().name(),
                decision.rejectReason(), Timestamp.from(decision.createdAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(RemedyLeg leg) {
        jdbc.update("INSERT INTO remedy_leg (remedy_leg_id, remedy_decision_id, contract_id, milestone_id,"
                        + " remedy_type, entry_type, fund_type, user_id, amount, request_event_id, status, attempts,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                leg.getRemedyLegId(), leg.getRemedyDecisionId(), leg.getContractId(), leg.getMilestoneId(),
                leg.getRemedyType().name(), leg.getEntryType().name(), leg.getFundType(), leg.getUserId(),
                leg.getAmount().amount(), leg.getRequestEventId(), leg.getStatus().name(),
                Timestamp.from(leg.getCreatedAt()), Timestamp.from(leg.getUpdatedAt()));
    }

    @Override
    public List<RemedyLeg> findByDecisionId(String remedyDecisionId) {
        return jdbc.query("SELECT " + LEG_COLUMNS + " FROM remedy_leg WHERE remedy_decision_id = ?"
                + " ORDER BY remedy_leg_id", LEG, remedyDecisionId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RemedyLeg> findByIdForUpdate(String remedyLegId) {
        return jdbc.query("SELECT " + LEG_COLUMNS + " FROM remedy_leg WHERE remedy_leg_id = ? FOR UPDATE", LEG,
                remedyLegId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(RemedyLeg leg) {
        int updated = jdbc.update("UPDATE remedy_leg SET status = ?, attempts = ?, next_attempt_at = ?,"
                        + " failure_reason = ?, ledger_entry_id = ?, updated_at = ? WHERE remedy_leg_id = ?",
                leg.getStatus().name(), leg.getAttempts(),
                leg.getNextAttemptAt() == null ? null : Timestamp.from(leg.getNextAttemptAt()),
                leg.getFailureReason(), leg.getLedgerEntryId(), Timestamp.from(leg.getUpdatedAt()),
                leg.getRemedyLegId());
        if (updated != 1) {
            throw new IllegalStateException("No remedy leg " + leg.getRemedyLegId());
        }
    }

    @Override
    public List<String> findWithRetryDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT remedy_leg_id FROM remedy_leg WHERE status = 'FAILED'"
                + " AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?", String.class, Timestamp.from(now),
                limit);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
