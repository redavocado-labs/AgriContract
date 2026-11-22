package com.agricontract.escrow.infrastructure.persistence;

import com.agricontract.escrow.domain.model.FundPool;
import com.agricontract.escrow.domain.model.PoolAmount;
import com.agricontract.escrow.domain.repository.OutstandingCommandRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcOutstandingCommandRepository implements OutstandingCommandRepository {

    // Settlement legs draw from the milestone's batch, deposit returns from the owner's deposit, remedy legs from
    // their own fund. A refused (FAILED) command is resent, so it still claims its money.
    private static final String IN_FLIGHT = """
            SELECT fund_type, milestone_id, amount FROM settlement_leg
             WHERE contract_id = ? AND status IN ('REQUESTED', 'FAILED')
            UNION ALL
            SELECT CASE leg WHEN 'BUYER' THEN 'BUYER_DEPOSIT' ELSE 'SELLER_DEPOSIT' END, NULL, amount
              FROM deposit_leg
             WHERE contract_id = ? AND status IN ('RETURN_REQUESTED', 'RETURN_FAILED')
            UNION ALL
            SELECT fund_type, milestone_id, amount FROM remedy_leg
             WHERE contract_id = ? AND status IN ('SENT', 'FAILED')
            """;

    private final JdbcTemplate jdbc;

    public JdbcOutstandingCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PoolAmount> inFlight(String contractId) {
        return jdbc.query(IN_FLIGHT, (rs, rowNum) -> new PoolAmount(
                FundPool.of(rs.getString(1), rs.getString(2)), rs.getBigDecimal(3)),
                contractId, contractId, contractId);
    }
}
