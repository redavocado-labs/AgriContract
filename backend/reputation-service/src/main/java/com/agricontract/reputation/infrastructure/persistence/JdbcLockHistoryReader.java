package com.agricontract.reputation.infrastructure.persistence;

import com.agricontract.reputation.application.port.LockHistoryReader;
import com.agricontract.reputation.domain.LockHistoryPolicy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLockHistoryReader implements LockHistoryReader {
    private final JdbcTemplate jdbc;

    public JdbcLockHistoryReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counts counts(UUID userId, Instant decidedAt) {
        String id = userId.toString();
        LocalDateTime end = LocalDateTime.ofInstant(decidedAt, ZoneOffset.UTC);
        LocalDateTime start = LocalDateTime.ofInstant(LockHistoryPolicy.repeatWindowStart(decidedAt), ZoneOffset.UTC);
        long completed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM completed_contract_fact
                WHERE (buyer_id = ? OR seller_id = ?) AND settled_at < ?
                """, Long.class, id, id, end);
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS prior_count,
                       COALESCE(SUM(CASE WHEN decided_at >= ? THEN 1 ELSE 0 END), 0) AS recent_count
                FROM lock_entry WHERE user_id = ? AND decided_at < ?
                """, (rs, row) -> new Counts(completed, rs.getLong("prior_count"), rs.getLong("recent_count")),
                start, id, end);
    }
}
