package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.RemedyRetryCommand;
import com.agricontract.escrow.application.port.RemedyCommandPublisher;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * OPERATOR RetryRemedyLegs (contract.remedy_retry_requested, D93), in the caller's transaction. Under the row locks of
 * every remedy leg of the contract, each FAILED leg (retry still scheduled or exhausted) starts a new retry cycle and
 * its original command is sent again, same eventId and payload (D27): amounts, payees and the decision never change.
 * COMPLETED and SENT legs are left alone. No leg, or no FAILED leg, sends nothing and is acked: OPERATOR may press
 * the button on an ACTIVE contract that has no remedy leg.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestartRemedyLegsUseCase {

    private final RemedyLegRepository remedyLegs;
    private final RemedyCommandPublisher bankCommands;
    private final Clock clock;

    /** Returns how many legs were resent. */
    public int execute(RemedyRetryCommand command) {
        List<RemedyLeg> legs = remedyLegs.findByContractIdForUpdate(command.contractId());
        Instant now = clock.instant();
        int resent = 0;
        for (RemedyLeg leg : legs) {
            if (!leg.restartRetryCycle(now)) {
                continue;
            }
            remedyLegs.update(leg);
            bankCommands.resend(leg);
            resent++;
            log.info("event=remedy_leg_cycle_restarted contractId={} remedyDecisionId={} remedyLegId={} entryType={}"
                            + " sourceEventId={} operator={}", leg.getContractId(), leg.getRemedyDecisionId(),
                    leg.getRemedyLegId(), leg.getEntryType(), leg.getRequestEventId(), command.operatorUserId());
        }
        if (resent == 0) {
            log.info("event=remedy_retry_nothing_failed contractId={} eventId={} legs={}", command.contractId(),
                    command.eventId(), legs.size());
        }
        return resent;
    }
}
