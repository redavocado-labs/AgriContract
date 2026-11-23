package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.dispute.SettlementProposal;
import com.agricontract.contract.domain.model.dispute.SettlementProposalStatus;

import java.time.Instant;
import java.util.Optional;

/** dispute_settlement_proposal (V26): at most one OPEN per milestone; a row is never deleted. */
public interface SettlementProposalRepository {

    /** Inserts an OPEN proposal; the caller holds the milestone lock and checked there is no OPEN one. */
    void insert(SettlementProposal proposal);

    /** The milestone's OPEN proposal, under its row lock. */
    Optional<SettlementProposal> findOpenByMilestoneIdForUpdate(String milestoneId);

    /** The milestone's OPEN proposal, plain read. */
    Optional<SettlementProposal> findOpenByMilestoneId(String milestoneId);

    /** OPEN becomes {@code status} at {@code at}; actorId is null when the system ended it (LAPSED). */
    void end(String proposalId, SettlementProposalStatus status, Instant at, String actorId);

    /** The milestone's OPEN proposal, if any, becomes LAPSED. Returns whether one did. */
    boolean lapseOpen(String milestoneId, Instant at);
}
