package com.agricontract.reputation.infrastructure.messaging;

import com.agricontract.reputation.application.exception.ImmutableFactConflictException;
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
}
