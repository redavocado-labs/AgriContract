package com.agricontract.escrow.infrastructure.persistence;

import com.agricontract.escrow.domain.model.ContractClearanceRequest;
import com.agricontract.escrow.domain.repository.ContractClearanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcContractClearanceRepository implements ContractClearanceRepository {

    private static final RowMapper<ContractClearanceRequest> ROW = (rs, rowNum) -> new ContractClearanceRequest(
            rs.getString("contract_id"),
            rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("checked_at") == null ? null : rs.getTimestamp("checked_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcContractClearanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(String contractId, Instant now) {
        Timestamp at = timestamp(now);
        jdbc.update("INSERT INTO contract_clearance_check (contract_id, requested_at, checked_at) VALUES (?, ?, NULL)"
                + " ON DUPLICATE KEY UPDATE requested_at = ?, checked_at = NULL", contractId, at, at);
    }

    @Override
    public List<ContractClearanceRequest> findUnchecked(int limit) {
        return jdbc.query("SELECT contract_id, requested_at, checked_at FROM contract_clearance_check"
                + " WHERE checked_at IS NULL ORDER BY requested_at LIMIT ?", ROW, limit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ContractClearanceRequest> findForUpdate(String contractId) {
        return jdbc.query("SELECT contract_id, requested_at, checked_at FROM contract_clearance_check"
                + " WHERE contract_id = ? FOR UPDATE", ROW, contractId).stream().findFirst();
    }

    @Override
    public boolean markChecked(String contractId, Instant requestedAt, Instant now) {
        return jdbc.update("UPDATE contract_clearance_check SET checked_at = ? WHERE contract_id = ?"
                + " AND requested_at = ? AND checked_at IS NULL", timestamp(now), contractId,
                timestamp(requestedAt)) == 1;
    }

    /** TIMESTAMP(6): stored to the microsecond, so a value read back compares equal to what was written. */
    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }
}
