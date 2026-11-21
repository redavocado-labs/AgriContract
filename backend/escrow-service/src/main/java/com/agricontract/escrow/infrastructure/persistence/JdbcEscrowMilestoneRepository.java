package com.agricontract.escrow.infrastructure.persistence;

import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.EscrowMilestoneStatus;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
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
public class JdbcEscrowMilestoneRepository implements EscrowMilestoneRepository {

    private static final String INSERT = """
            INSERT INTO escrow_milestone (milestone_id, contract_id, milestone_index, buyer_id, batch_amount,
                                          funding_requested_at, funding_request_event_id, status, request_event_id,
                                          created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_FOR_UPDATE = """
            SELECT milestone_id, contract_id, milestone_index, buyer_id, batch_amount, funding_requested_at,
                   funding_request_event_id, status, request_event_id, ledger_entry_id, locked_at, failed_attempts,
                   next_attempt_at, last_failure_reason, created_at, updated_at
            FROM escrow_milestone WHERE milestone_id = ? FOR UPDATE
            """;
    private static final String UPDATE_RESULT = """
            UPDATE escrow_milestone SET status = ?, ledger_entry_id = ?, locked_at = ?, failed_attempts = ?,
                                        next_attempt_at = ?, last_failure_reason = ?, updated_at = ?
            WHERE milestone_id = ?
            """;

    private static final RowMapper<EscrowMilestone> ROW = (rs, rowNum) -> EscrowMilestone.reconstitute(
            rs.getString("milestone_id"),
            rs.getString("contract_id"),
            rs.getInt("milestone_index"),
            rs.getString("buyer_id"),
            new Money(rs.getBigDecimal("batch_amount")),
            rs.getTimestamp("funding_requested_at").toInstant(),
            rs.getString("funding_request_event_id"),
            EscrowMilestoneStatus.valueOf(rs.getString("status")),
            rs.getString("request_event_id"),
            rs.getString("ledger_entry_id"),
            instant(rs.getTimestamp("locked_at")),
            rs.getInt("failed_attempts"),
            instant(rs.getTimestamp("next_attempt_at")),
            rs.getString("last_failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcEscrowMilestoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(EscrowMilestone milestone) {
        jdbc.update(INSERT,
                milestone.getMilestoneId(),
                milestone.getContractId(),
                milestone.getMilestoneIndex(),
                milestone.getBuyerId(),
                milestone.getBatchAmount().amount(),
                Timestamp.from(milestone.getFundingRequestedAt()),
                milestone.getFundingRequestEventId(),
                milestone.getStatus().name(),
                milestone.getRequestEventId(),
                Timestamp.from(milestone.getCreatedAt()),
                Timestamp.from(milestone.getUpdatedAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EscrowMilestone> findByIdForUpdate(String milestoneId) {
        return jdbc.query(SELECT_FOR_UPDATE, ROW, milestoneId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(EscrowMilestone milestone) {
        int updated = jdbc.update(UPDATE_RESULT,
                milestone.getStatus().name(),
                milestone.getLedgerEntryId(),
                timestamp(milestone.getLockedAt()),
                milestone.getFailedAttempts(),
                timestamp(milestone.getNextAttemptAt()),
                milestone.getLastFailureReason(),
                Timestamp.from(milestone.getUpdatedAt()),
                milestone.getMilestoneId());
        if (updated != 1) {
            throw new IllegalStateException("No escrow milestone " + milestone.getMilestoneId());
        }
    }

    @Override
    public List<String> findWithRetryDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT milestone_id FROM escrow_milestone"
                        + " WHERE status = 'FUNDING_PENDING' AND next_attempt_at <= ?"
                        + " ORDER BY next_attempt_at LIMIT ?",
                String.class, Timestamp.from(now), limit);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
