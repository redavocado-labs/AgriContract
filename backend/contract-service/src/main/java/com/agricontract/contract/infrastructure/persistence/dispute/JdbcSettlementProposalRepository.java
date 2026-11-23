package com.agricontract.contract.infrastructure.persistence.dispute;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.dispute.SettlementProposal;
import com.agricontract.contract.domain.model.dispute.SettlementProposalStatus;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.repository.SettlementProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** dispute_settlement_proposal (V26). */
@Repository
@RequiredArgsConstructor
public class JdbcSettlementProposalRepository implements SettlementProposalRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(SettlementProposal p) {
        jdbc.update("INSERT INTO dispute_settlement_proposal (proposal_id, contract_id, milestone_id,"
                        + " agreed_seller_entitlement_amount, proposed_by_role, proposed_by_actor_id, proposed_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", p.proposalId(), p.contractId(), p.milestoneId(),
                p.agreedSellerEntitlementAmount().value(), p.proposedByRole().name(), p.proposedByActorId(),
                Timestamp.from(p.proposedAt()), p.status().name());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SettlementProposal> findOpenByMilestoneIdForUpdate(String milestoneId) {
        return find("SELECT * FROM dispute_settlement_proposal WHERE milestone_id = ? AND status = 'OPEN' FOR UPDATE",
                milestoneId);
    }

    @Override
    public Optional<SettlementProposal> findOpenByMilestoneId(String milestoneId) {
        return find("SELECT * FROM dispute_settlement_proposal WHERE milestone_id = ? AND status = 'OPEN'",
                milestoneId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void end(String proposalId, SettlementProposalStatus status, Instant at, String actorId) {
        if (status == SettlementProposalStatus.OPEN) {
            throw new IllegalArgumentException("A proposal cannot end as OPEN");
        }
        int updated = jdbc.update("UPDATE dispute_settlement_proposal SET status = ?, ended_at = ?,"
                        + " ended_by_actor_id = ? WHERE proposal_id = ? AND status = 'OPEN'", status.name(),
                Timestamp.from(at), actorId, proposalId);
        if (updated != 1) {
            throw new IllegalStateException("Settlement proposal " + proposalId + " is not OPEN");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lapseOpen(String milestoneId, Instant at) {
        return jdbc.update("UPDATE dispute_settlement_proposal SET status = 'LAPSED', ended_at = ?"
                + " WHERE milestone_id = ? AND status = 'OPEN'", Timestamp.from(at), milestoneId) > 0;
    }

    private Optional<SettlementProposal> find(String sql, String id) {
        return jdbc.query(sql, (rs, n) -> {
            Timestamp ended = rs.getTimestamp("ended_at");
            return new SettlementProposal(rs.getString("proposal_id"), rs.getString("contract_id"),
                    rs.getString("milestone_id"), new VndAmount(rs.getBigDecimal("agreed_seller_entitlement_amount")),
                    SignerRole.valueOf(rs.getString("proposed_by_role")), rs.getString("proposed_by_actor_id"),
                    rs.getTimestamp("proposed_at").toInstant(),
                    SettlementProposalStatus.valueOf(rs.getString("status")),
                    ended == null ? null : ended.toInstant(), rs.getString("ended_by_actor_id"));
        }, id).stream().findFirst();
    }
}
