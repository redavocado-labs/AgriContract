package com.agricontract.reputation.infrastructure.messaging;

import com.agricontract.reputation.application.exception.ImmutableFactConflictException;
import com.agricontract.reputation.application.dto.ProcessLockoutCommand;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RemedyDeliveryReceipt {
    private static final String CONSUMER = "reputation-service.remedy-finalized";
    private final JdbcTemplate jdbc;

    public RemedyDeliveryReceipt(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(UUID eventId, String fingerprint) {
        try {
            jdbc.update("INSERT INTO processed_event (consumer_name,event_id,event_type) VALUES (?,?,'remedy.finalized')",
                    CONSUMER, eventId.toString());
        } catch (DuplicateKeyException duplicate) {
            var fingerprints = jdbc.query("SELECT payload_sha256 FROM remedy_delivery_fingerprint WHERE consumer_name=? AND event_id=?",
                    (rs, row) -> rs.getString(1), CONSUMER, eventId.toString());
            if (fingerprints.size() != 1 || !fingerprints.getFirst().equals(fingerprint)) {
                throw new ImmutableFactConflictException();
            }
            return false;
        }
        jdbc.update("INSERT INTO remedy_delivery_fingerprint (consumer_name,event_id,payload_sha256) VALUES (?,?,?)",
                CONSUMER, eventId.toString(), fingerprint);
        return true;
    }

    /** Claim the decision before taking any user mutex, including for decisions without a user lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimDecision(ProcessLockoutCommand command, String fingerprint) {
        try {
            jdbc.update("""
                    INSERT INTO remedy_lock_evidence
                    (remedy_decision_id,buyer_id,seller_id,breach_reason_code,settled_milestone_count,decision_fingerprint)
                    VALUES (?,?,?,?,?,?)
                    """, command.remedyDecisionId().toString(), command.buyerId().toString(),
                    command.sellerId().toString(), command.breachReasonCode(),
                    command.settledMilestoneCount().toString(), fingerprint);
            return true;
        } catch (DuplicateKeyException duplicate) {
            var existing = jdbc.query("SELECT decision_fingerprint FROM remedy_lock_evidence WHERE remedy_decision_id=?",
                    (rs, row) -> rs.getString(1), command.remedyDecisionId().toString());
            if (existing.size() != 1 || existing.getFirst() == null) {
                throw new IllegalStateException("Existing remedy evidence lacks a verifiable decision fingerprint");
            }
            if (!existing.getFirst().equals(fingerprint)) {
                throw new ImmutableFactConflictException();
            }
            return false;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rememberEvent(UUID eventId, UUID decisionId) {
        var existing = jdbc.query("SELECT remedy_decision_id FROM remedy_event_identity WHERE source_event_id=?",
                (rs, row) -> rs.getString(1), eventId.toString());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO remedy_event_identity (source_event_id,remedy_decision_id) VALUES (?,?)",
                    eventId.toString(), decisionId.toString());
        } else if (existing.size() != 1 || !existing.getFirst().equals(decisionId.toString())) {
            throw new ImmutableFactConflictException();
        }
    }
}
