package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.port.RemedyCommandPublisher;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resends a refused remedy leg when its retry is due (#249): the original command, same eventId and payload, so
 * bank-service dedups on it (D27). Locks the leg first, like a bank result, so a result arriving meanwhile is never
 * overwritten.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryRemedyLegsUseCase {

    private static final int BATCH = 100;

    private final RemedyLegRepository remedyLegs;
    private final RemedyCommandPublisher bankCommands;
    private final Clock clock;

    public List<String> legsWithRetryDue() {
        return remedyLegs.findWithRetryDue(clock.instant(), BATCH);
    }

    public boolean retryDue(String remedyLegId) {
        Instant now = clock.instant();
        Optional<RemedyLeg> found = remedyLegs.findByIdForUpdate(remedyLegId);
        if (found.isEmpty() || !found.get().isRetryDue(now)) {
            return false;
        }
        RemedyLeg leg = found.get();
        leg.retryRequested(now);
        remedyLegs.update(leg);
        bankCommands.resend(leg);
        log.info("event=remedy_leg_retried remedyLegId={} contractId={} entryType={} failures={} sourceEventId={}",
                leg.getRemedyLegId(), leg.getContractId(), leg.getEntryType(), leg.getAttempts(),
                leg.getRequestEventId());
        return true;
    }
}
