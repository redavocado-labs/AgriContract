package com.agricontract.contract.infrastructure.persistence.inspection;

import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.inspection.Level15Request;
import com.agricontract.contract.domain.repository.Level15RequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** level1_5_request (V26). */
@Repository
@RequiredArgsConstructor
public class JdbcLevel15RequestRepository implements Level15RequestRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Level15Request r) {
        jdbc.update("INSERT INTO level1_5_request (request_id, milestone_id, reason, initiated_by_actor_id,"
                        + " requested_at, ended_at, end_reason) VALUES (?, ?, ?, ?, ?, ?, ?)", r.requestId(),
                r.milestoneId(), r.reason().name(), r.initiatedByActorId(), Timestamp.from(r.requestedAt()),
                r.endedAt() == null ? null : Timestamp.from(r.endedAt()),
                r.endReason() == null ? null : r.endReason().name());
    }

    @Override
    public Optional<Level15Request> findLatestOpen(String milestoneId) {
        return jdbc.query("SELECT * FROM level1_5_request WHERE milestone_id = ? AND ended_at IS NULL"
                + " ORDER BY requested_at DESC, request_id DESC LIMIT 1", (rs, n) -> new Level15Request(
                rs.getString("request_id"), rs.getString("milestone_id"),
                InspectionRequestReason.valueOf(rs.getString("reason")), rs.getString("initiated_by_actor_id"),
                rs.getTimestamp("requested_at").toInstant(), null, null), milestoneId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int endOpen(String milestoneId, Level15Request.EndReason reason, Instant at) {
        return jdbc.update("UPDATE level1_5_request SET ended_at = ?, end_reason = ? WHERE milestone_id = ?"
                + " AND ended_at IS NULL", Timestamp.from(at), reason.name(), milestoneId);
    }
}
