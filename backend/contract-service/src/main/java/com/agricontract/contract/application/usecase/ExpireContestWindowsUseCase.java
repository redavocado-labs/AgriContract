package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * GB-50, D85: a decision with fault whose contest window (contestStatus OPEN) has passed with no contest becomes
 * EXPIRED and its RemedyDecision is finalized ({@link FinalizeRemedyDecisionUseCase}: the milestone is SETTLED,
 * remedy.finalized and milestone.dispute_resolved are written). Each case is handled in its own transaction under the
 * contract lock then the case lock and re-checked there, so a contest that won the race is left alone, and one failing
 * case does not stop the next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpireContestWindowsUseCase {
    private static final int BATCH = 100;

    private final BreachCaseRepository breachCases;
    private final ContractRepository contracts;
    private final RemedyDecisionRepository remedyDecisions;
    private final FinalizeRemedyDecisionUseCase finalizeRemedy;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** Returns how many windows were closed. */
    public int execute() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<String> candidates = breachCases.findIdsWithExpiredContestWindow(now, BATCH);
        int handled = 0;
        for (String breachCaseId : candidates) {
            try {
                if (Boolean.TRUE.equals(transactions.inTransaction(() -> expireOne(breachCaseId, now)))) {
                    handled++;
                }
            } catch (RuntimeException e) {
                log.error("event=contest_window_expiry_failed breachCaseId={} reason={}", breachCaseId,
                        e.getMessage(), e);
            }
        }
        return handled;
    }

    private boolean expireOne(String breachCaseId, Instant now) {
        BreachCase peek = breachCases.findById(breachCaseId).orElse(null);
        if (peek == null) {
            return false;
        }
        // Contract lock first, as every contract-service write does, then the case.
        Contract contract = contracts.findByIdForUpdate(new ContractId(peek.contractId())).orElse(null);
        BreachCase breachCase = breachCases.findByIdForUpdate(breachCaseId).orElse(null);
        if (contract == null || breachCase == null || breachCase.contestStatus() != ContestStatus.OPEN
                || breachCase.contestDeadline() == null || now.isBefore(breachCase.contestDeadline())) {
            return false;
        }
        RemedyDecision decision = remedyDecisions.findByBreachCaseId(breachCaseId).orElseThrow(
                () -> new IllegalStateException("Breach case " + breachCaseId
                        + " has an open contest window but no remedy decision"));
        breachCases.update(breachCase.expireContestWindow(now));
        finalizeRemedy.execute(decision.remedyDecisionId());
        log.info("event=contest_window_expired breachCaseId={} contractId={} remedyDecisionId={}", breachCaseId,
                peek.contractId(), decision.remedyDecisionId());
        return true;
    }
}
