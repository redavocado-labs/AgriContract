package com.agricontract.reputation.infrastructure.persistence;

import com.agricontract.reputation.application.dto.ProcessLockoutCommand;
import com.agricontract.reputation.application.exception.ImmutableFactConflictException;
import com.agricontract.reputation.application.port.RemedyDecisionLedger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRemedyDecisionLedger implements RemedyDecisionLedger {
    private final JdbcTemplate jdbc;

    public JdbcRemedyDecisionLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(ProcessLockoutCommand command, String fingerprint) {
        boolean fresh;
        try {
            jdbc.update("""
                    INSERT INTO remedy_lock_evidence
                    (remedy_decision_id,buyer_id,seller_id,breach_reason_code,settled_milestone_count,decision_fingerprint)
                    VALUES (?,?,?,?,?,?)
                    """, command.remedyDecisionId().toString(), command.buyerId().toString(),
                    command.sellerId().toString(), command.breachReasonCode(), command.settledMilestoneCount(), fingerprint);
            fresh = true;
        } catch (DuplicateKeyException duplicate) {
            var existing = jdbc.query("SELECT decision_fingerprint FROM remedy_lock_evidence WHERE remedy_decision_id=?",
                    (rs, row) -> rs.getString(1), command.remedyDecisionId().toString());
            if (existing.size() != 1 || !existing.getFirst().equals(fingerprint)) throw new ImmutableFactConflictException();
            fresh = false;
        }
        try {
            jdbc.update("INSERT INTO remedy_event_identity (source_event_id,remedy_decision_id) VALUES (?,?)",
                    command.sourceEventId().toString(), command.remedyDecisionId().toString());
        } catch (DuplicateKeyException duplicate) {
            var existing = jdbc.query("SELECT remedy_decision_id FROM remedy_event_identity WHERE source_event_id=?",
                    (rs, row) -> rs.getString(1), command.sourceEventId().toString());
            if (existing.size() != 1 || !existing.getFirst().equals(command.remedyDecisionId().toString()))
                throw new ImmutableFactConflictException();
        }
        return fresh;
    }
}
