package com.agricontract.reputation.infrastructure.persistence;

import com.agricontract.reputation.application.port.LockEntryWriter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
}
