package com.agricontract.contract.infrastructure.persistence.inspection;

import com.agricontract.contract.domain.model.inspection.Level15Assignment;
import com.agricontract.contract.domain.repository.Level15AssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

/** level1_5_assignment (V26). */
@Repository
@RequiredArgsConstructor
public class JdbcLevel15AssignmentRepository implements Level15AssignmentRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(Level15Assignment a) {
        return jdbc.update("INSERT IGNORE INTO level1_5_assignment (commission_id, milestone_id, assigned_at,"
                        + " free_cancel_until, report_due_at) VALUES (?, ?, ?, ?, ?)", a.commissionId(),
                a.milestoneId(), Timestamp.from(a.assignedAt()), Timestamp.from(a.freeCancelUntil()),
                Timestamp.from(a.reportDueAt())) == 1;
    }

    @Override
    public Optional<Level15Assignment> findLatest(String milestoneId) {
        return jdbc.query("SELECT * FROM level1_5_assignment WHERE milestone_id = ?"
                        + " ORDER BY assigned_at DESC, commission_id DESC LIMIT 1",
                (rs, n) -> new Level15Assignment(rs.getString("commission_id"), rs.getString("milestone_id"),
                        rs.getTimestamp("assigned_at").toInstant(), rs.getTimestamp("free_cancel_until").toInstant(),
                        rs.getTimestamp("report_due_at").toInstant()), milestoneId).stream().findFirst();
    }
}
