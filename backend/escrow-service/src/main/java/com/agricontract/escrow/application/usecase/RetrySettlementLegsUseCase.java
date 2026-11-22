package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.port.SettlementCommandPublisher;
import com.agricontract.escrow.domain.model.SettlementLeg;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resends a refused settlement leg when its retry is due (#248): the original command, same eventId and payload, so
 * bank-service dedups on it (D27). Locks the milestone then its legs, the same order as a bank result, so a result
 * arriving meanwhile is never overwritten.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrySettlementLegsUseCase {

    private static final int BATCH = 100;

    private final EscrowMilestoneRepository milestones;
    private final SettlementLegRepository settlementLegs;
    private final SettlementCommandPublisher bankCommands;
    private final Clock clock;

    public List<String> legsWithRetryDue() {
        return settlementLegs.findWithRetryDue(clock.instant(), BATCH);
    }

    public boolean retryDue(String legId) {
        Optional<String> milestoneId = settlementLegs.findMilestoneIdByLegId(legId);
        if (milestoneId.isEmpty() || milestones.findByIdForUpdate(milestoneId.get()).isEmpty()) {
            return false;
        }
        Instant now = clock.instant();
        Optional<SettlementLeg> found = settlementLegs.findByMilestoneIdForUpdate(milestoneId.get()).stream()
                .filter(l -> l.getLegId().equals(legId))
                .findFirst();
        if (found.isEmpty() || !found.get().isRetryDue(now)) {
            return false;
        }
        SettlementLeg leg = found.get();
        leg.retryRequested(now);
        settlementLegs.update(leg);
        bankCommands.resend(leg);
        log.info("event=settlement_leg_retried contractId={} milestoneId={} entryType={} failures={} sourceEventId={}",
                leg.getContractId(), leg.getMilestoneId(), leg.getType(), leg.getFailedAttempts(),
                leg.getRequestEventId());
        return true;
    }
}
