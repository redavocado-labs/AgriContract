package com.agricontract.reputation.application.usecase;

import com.agricontract.reputation.application.dto.ProcessLockoutCommand;
import com.agricontract.reputation.application.exception.ImmutableFactConflictException;
import com.agricontract.reputation.application.port.LockEntryWriter;
import com.agricontract.reputation.application.port.LockHistoryReader;
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

    public ProcessLockoutUseCase(LockEventLifecycle lifecycle, LockHistoryReader history, LockEntryWriter entries) {
        this.lifecycle = lifecycle;
        this.history = history;
        this.entries = entries;
    }

    public boolean execute(ProcessLockoutCommand command) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.sourceEventId());
        Objects.requireNonNull(command.remedyDecisionId());
        Objects.requireNonNull(command.contractId());
        Objects.requireNonNull(command.decidedAt());
        if (Objects.requireNonNull(command.settledMilestoneCount()).signum() < 0) {
            throw new IllegalArgumentException("Invalid settled milestone count");
        }
        var party = RemedyAttribution.select(command.reputationEligible(), command.finalBreachingRole(),
                command.buyerId(), command.sellerId(), command.breachReasonCode());
        if (party.isEmpty()) {
            return false;
        }
        var penalized = party.orElseThrow();
        Instant decided = command.decidedAt().truncatedTo(ChronoUnit.MICROS);
        return lifecycle.recordRemedy(penalized.userId(), command.remedyDecisionId(), penalized.reason(),
                command.correlationId(), () -> {
                    var existingDecision = entries.decisionForEvent(command.sourceEventId());
                    if (existingDecision.isPresent() && !existingDecision.orElseThrow().equals(command.remedyDecisionId())) {
                        throw new ImmutableFactConflictException();
                    }
                    var prior = entries.findBySourceOrDecision(command.sourceEventId(), command.remedyDecisionId());
                    if (!prior.isEmpty()) {
                        if (prior.size() != 1 || !prior.getFirst().remedyDecisionId().equals(command.remedyDecisionId())
                                || !prior.getFirst().contractId().equals(command.contractId())
                                || !prior.getFirst().userId().equals(penalized.userId())
                                || !prior.getFirst().penalizedRole().equals(penalized.role().name())
                                || !prior.getFirst().decidedAt().equals(decided)
                                || !command.buyerId().equals(prior.getFirst().buyerId())
                                || !command.sellerId().equals(prior.getFirst().sellerId())
                                || !penalized.reason().equals(prior.getFirst().reason())
                                || !command.settledMilestoneCount().equals(prior.getFirst().settledMilestones())) {
                            throw new ImmutableFactConflictException();
                        }
                        if (existingDecision.isEmpty()) {
                            entries.recordEvent(command.sourceEventId(), command.remedyDecisionId());
                        }
                        return null;
                    }
                    var counts = history.counts(penalized.userId(), decided);
                    var snapshot = LockDurationPolicy.calculate(penalized.reason(),
                            LockHistoryPolicy.repeatMultiplier(counts.recentLocks()),
                            LockHistoryPolicy.trackRecordMultiplier(counts.completed(), counts.priorLocks()),
                            command.settledMilestoneCount().signum());
                    if (snapshot.unknownReason()) {
                        LOG.warn("event=reputation_unknown_eligible_breach_reason");
                    }
                    Instant expiry = decided.plus(snapshot.lockDurationDays(), ChronoUnit.DAYS);
                    entries.insert(new LockEntryWriter.Entry(UUID.randomUUID(), command.sourceEventId(),
                            command.remedyDecisionId(), command.contractId(), penalized.userId(), penalized.role().name(),
                            snapshot.baseDays(), snapshot.repeatOffenseMultiplier(), snapshot.trackRecordMultiplier(),
                            snapshot.zeroProgressMultiplier(), snapshot.lockDurationDays(), decided, expiry));
                    entries.recordEvidence(command.remedyDecisionId(), command.buyerId(), command.sellerId(),
                            penalized.reason(), command.settledMilestoneCount());
                    entries.recordEvent(command.sourceEventId(), command.remedyDecisionId());
                    return expiry;
                });
    }
}
