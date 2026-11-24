package com.agricontract.reputation.application.usecase;

import com.agricontract.reputation.application.dto.ProcessLockoutCommand;
import com.agricontract.reputation.application.port.LockEntryWriter;
import com.agricontract.reputation.application.port.LockHistoryReader;
import com.agricontract.reputation.application.port.RemedyDecisionLedger;
import com.agricontract.reputation.application.port.TransactionRunner;
import com.agricontract.reputation.domain.LockDurationPolicy;
import com.agricontract.reputation.domain.LockHistoryPolicy;
import com.agricontract.reputation.domain.RemedyAttribution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProcessLockoutUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessLockoutUseCase.class);
    private final LockEventLifecycle lifecycle;
    private final LockHistoryReader history;
    private final LockEntryWriter entries;
    private final RemedyDecisionLedger decisions;
    private final TransactionRunner transactions;

    public ProcessLockoutUseCase(LockEventLifecycle lifecycle, LockHistoryReader history, LockEntryWriter entries,
                                 RemedyDecisionLedger decisions, TransactionRunner transactions) {
        this.lifecycle = lifecycle;
        this.history = history;
        this.entries = entries;
        this.decisions = decisions;
        this.transactions = transactions;
    }

    public boolean execute(ProcessLockoutCommand command, String fingerprint) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.sourceEventId());
        Objects.requireNonNull(command.remedyDecisionId());
        Objects.requireNonNull(command.contractId());
        Objects.requireNonNull(command.decidedAt());
        Objects.requireNonNull(fingerprint);
        if (command.settledMilestoneCount() < 0) {
            throw new IllegalArgumentException("Invalid settled milestone count");
        }
        return transactions.inTransaction(() -> process(command, fingerprint));
    }

    private boolean process(ProcessLockoutCommand command, String fingerprint) {
        if (!decisions.claim(command, fingerprint)) return false;
        var party = RemedyAttribution.select(command.reputationEligible(), command.finalBreachingRole(),
                command.buyerId(), command.sellerId(), command.breachReasonCode());
        if (party.isEmpty()) {
            return false;
        }
        var penalized = party.orElseThrow();
        Instant decided = command.decidedAt().truncatedTo(ChronoUnit.MICROS);
        return lifecycle.recordRemedy(penalized.userId(), command.remedyDecisionId(), penalized.reason(),
                command.correlationId(), () -> {
                    var counts = history.counts(penalized.userId(), decided);
                    var snapshot = LockDurationPolicy.calculate(penalized.reason(),
                            LockHistoryPolicy.repeatMultiplier(counts.recentLocks()),
                            LockHistoryPolicy.trackRecordMultiplier(counts.completed(), counts.priorLocks()),
                            command.settledMilestoneCount() == 0);
                    if (snapshot.unknownReason()) {
                        LOG.warn("event=reputation_unknown_eligible_breach_reason remedyDecisionId={} breachReasonCode={}",
                                command.remedyDecisionId(), command.breachReasonCode());
                    }
                    Instant expiry = decided.plus(snapshot.lockDurationDays(), ChronoUnit.DAYS);
                    entries.insert(new LockEntryWriter.Entry(UUID.randomUUID(), command.sourceEventId(),
                            command.remedyDecisionId(), command.contractId(), penalized.userId(), penalized.role().name(),
                            snapshot.baseDays(), snapshot.repeatOffenseMultiplier(), snapshot.trackRecordMultiplier(),
                            snapshot.zeroProgressMultiplier(), snapshot.lockDurationDays(), decided, expiry));
                    return expiry;
                });
    }
}
