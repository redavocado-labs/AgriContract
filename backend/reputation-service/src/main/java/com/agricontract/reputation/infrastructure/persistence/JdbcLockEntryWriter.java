package com.agricontract.reputation.infrastructure.persistence;

import com.agricontract.reputation.application.port.LockEntryWriter;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLockEntryWriter implements LockEntryWriter {
    private final JdbcTemplate jdbc;

    public JdbcLockEntryWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Entry e) {
        jdbc.update("""
                INSERT INTO lock_entry (entry_id, source_event_id, remedy_decision_id, contract_id, user_id,
                    penalized_role, base_days, repeat_offense_multiplier, track_record_multiplier,
                    zero_progress_multiplier, lock_duration_days, decided_at, locked_until, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())
                """, e.entryId().toString(), e.sourceEventId().toString(), e.remedyDecisionId().toString(),
                e.contractId().toString(), e.userId().toString(), e.penalizedRole(), e.baseDays(),
                e.repeatMultiplier(), e.trackMultiplier(), e.zeroProgressMultiplier(), e.durationDays(),
                LocalDateTime.ofInstant(e.decidedAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(e.lockedUntil(), ZoneOffset.UTC));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Existing> findBySourceOrDecision(UUID sourceEventId, UUID remedyDecisionId) {
        return jdbc.query("""
                SELECT l.source_event_id, l.remedy_decision_id, l.contract_id, l.user_id, l.penalized_role, l.decided_at,
                       e.buyer_id, e.seller_id, e.breach_reason_code, e.settled_milestone_count
                FROM lock_entry l LEFT JOIN remedy_lock_evidence e ON e.remedy_decision_id = l.remedy_decision_id
                WHERE l.source_event_id = ? OR l.remedy_decision_id = ?
                """, (rs, n) -> new Existing(UUID.fromString(rs.getString("source_event_id")),
                UUID.fromString(rs.getString("remedy_decision_id")), UUID.fromString(rs.getString("contract_id")),
                UUID.fromString(rs.getString("user_id")), rs.getString("penalized_role"),
                rs.getObject("decided_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                nullableUuid(rs.getString("buyer_id")), nullableUuid(rs.getString("seller_id")),
                rs.getString("breach_reason_code"), nullableInteger(rs.getString("settled_milestone_count"))),
                sourceEventId.toString(), remedyDecisionId.toString());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidence(UUID decisionId, UUID buyerId, UUID sellerId, String reason, BigInteger settledMilestones) {
        jdbc.update("INSERT INTO remedy_lock_evidence VALUES (?, ?, ?, ?, ?)", decisionId.toString(),
                buyerId.toString(), sellerId.toString(), reason, settledMilestones.toString());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> decisionForEvent(UUID sourceEventId) {
        return jdbc.query("SELECT remedy_decision_id FROM remedy_event_identity WHERE source_event_id = ?",
                (rs, n) -> UUID.fromString(rs.getString(1)), sourceEventId.toString()).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(UUID sourceEventId, UUID decisionId) {
        jdbc.update("INSERT INTO remedy_event_identity (source_event_id, remedy_decision_id) VALUES (?, ?)",
                sourceEventId.toString(), decisionId.toString());
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static BigInteger nullableInteger(String value) {
        return value == null ? null : new BigInteger(value);
    }
}
