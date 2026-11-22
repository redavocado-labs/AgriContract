package com.agricontract.escrow.infrastructure.persistence;

import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.SettlementLeg;
import com.agricontract.escrow.domain.model.SettlementLegStatus;
import com.agricontract.escrow.domain.model.SettlementLegType;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
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
public class JdbcSettlementLegRepository implements SettlementLegRepository {

    private static final String INSERT = """
            INSERT INTO settlement_leg (leg_id, contract_id, milestone_id, trigger_event_id, entry_type, fund_type,
                                        user_id, amount, request_event_id, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_FOR_UPDATE = """
            SELECT leg_id, contract_id, milestone_id, trigger_event_id, entry_type, fund_type, user_id, amount,
                   request_event_id, status, ledger_entry_id, failed_attempts, next_attempt_at, last_failure_reason,
                   created_at, updated_at
            FROM settlement_leg WHERE milestone_id = ? ORDER BY entry_type FOR UPDATE
            """;
    private static final String UPDATE_RESULT = """
            UPDATE settlement_leg SET status = ?, ledger_entry_id = ?, failed_attempts = ?, next_attempt_at = ?,
                                      last_failure_reason = ?, updated_at = ?
            WHERE leg_id = ?
            """;

    private static final RowMapper<SettlementLeg> ROW = (rs, rowNum) -> SettlementLeg.reconstitute(
            rs.getString("leg_id"),
            rs.getString("contract_id"),
            rs.getString("milestone_id"),
            rs.getString("trigger_event_id"),
            SettlementLegType.valueOf(rs.getString("entry_type")),
            rs.getString("fund_type"),
            rs.getString("user_id"),
            new Money(rs.getBigDecimal("amount")),
            rs.getString("request_event_id"),
            SettlementLegStatus.valueOf(rs.getString("status")),
            rs.getString("ledger_entry_id"),
            rs.getInt("failed_attempts"),
            instant(rs.getTimestamp("next_attempt_at")),
            rs.getString("last_failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcSettlementLegRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(SettlementLeg leg) {
        jdbc.update(INSERT,
                leg.getLegId(),
                leg.getContractId(),
                leg.getMilestoneId(),
                leg.getTriggerEventId(),
                leg.getType().name(),
                leg.getFundType(),
                leg.getUserId(),
                leg.getAmount().amount(),
                leg.getRequestEventId(),
                leg.getStatus().name(),
                Timestamp.from(leg.getCreatedAt()),
                Timestamp.from(leg.getUpdatedAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SettlementLeg> findByMilestoneIdForUpdate(String milestoneId) {
        return jdbc.query(SELECT_FOR_UPDATE, ROW, milestoneId);
    }

    @Override
    public Optional<String> findMilestoneIdByLegId(String legId) {
        return jdbc.queryForList("SELECT milestone_id FROM settlement_leg WHERE leg_id = ?", String.class, legId)
                .stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SettlementLeg leg) {
        int updated = jdbc.update(UPDATE_RESULT,
                leg.getStatus().name(),
                leg.getLedgerEntryId(),
                leg.getFailedAttempts(),
                timestamp(leg.getNextAttemptAt()),
                leg.getLastFailureReason(),
                Timestamp.from(leg.getUpdatedAt()),
                leg.getLegId());
        if (updated != 1) {
            throw new IllegalStateException("No settlement leg " + leg.getLegId());
        }
    }

    @Override
    public List<String> findWithRetryDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT leg_id FROM settlement_leg WHERE status = 'FAILED' AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at LIMIT ?", String.class, Timestamp.from(now), limit);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
