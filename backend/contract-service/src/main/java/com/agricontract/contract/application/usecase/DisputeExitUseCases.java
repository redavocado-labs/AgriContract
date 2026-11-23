package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.DisputeExitCommands;
import com.agricontract.contract.application.dto.IssuedOtp;
import com.agricontract.contract.application.dto.OtpEmail;
import com.agricontract.contract.application.dto.SignChallengeView;
import com.agricontract.contract.application.dto.UserInfo;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.exception.OtpEmailNotSentException;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.OtpCodes;
import com.agricontract.contract.application.port.OtpEmailPort;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.port.UserPort;
import com.agricontract.contract.application.service.DisputeExitStep;
import com.agricontract.contract.application.service.OtpChallengeIssuer;
import com.agricontract.contract.application.service.SignerEligibility;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.DisputeExitConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.dispute.DisputeExitType;
import com.agricontract.contract.domain.model.dispute.DisputeExitWindow;
import com.agricontract.contract.domain.model.dispute.SettlementProposal;
import com.agricontract.contract.domain.model.dispute.SettlementProposalStatus;
import com.agricontract.contract.domain.model.signature.OtpChallenge;
import com.agricontract.contract.domain.model.signature.OtpPolicy;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.ConsentEvidenceRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.DisputeExitChallengeRepository;
import com.agricontract.contract.domain.repository.OtpChallengeRepository;
import com.agricontract.contract.domain.repository.SettlementProposalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Ending a milestone dispute by the parties themselves (D57, D92, golden-flow-api dispute/concede/* and
 * dispute/settlement/*). High-risk tier like the D42 cancel request: checkbox CONCEDE_DISPUTE / SETTLE_DISPUTE bound to
 * the contract's signedContentHash, a fresh login (auth_time within 300 s) and an emailed OTP whose challenge is bound
 * to the milestone and, for a settlement, to the amount. Nothing changes until verify. API order: role (endpoint),
 * contract and milestone (404), party (403), the body (400), the state (409). One transaction per call, locks in the
 * contract-service order: contract, (OTP challenge), breach case, milestone, remedy decision, then the proposal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeExitUseCases {
    static final String INITIATE_CONCEDE = "INITIATE_DISPUTE_CONCESSION";
    static final String VERIFY_CONCEDE = "VERIFY_DISPUTE_CONCESSION";
    static final String INITIATE_SETTLE = "INITIATE_DISPUTE_SETTLEMENT";
    static final String VERIFY_SETTLE = "VERIFY_DISPUTE_SETTLEMENT";
    static final String WITHDRAW_SETTLE = "WITHDRAW_DISPUTE_SETTLEMENT";

    private final SignerEligibility eligibility;
    private final UserPort users;
    private final IdempotencyStore idempotencyStore;
    private final ContractRepository contracts;
    private final DisputeExitStep step;
    private final DisputeExitConsentStatements statements;
    private final ConsentEvidenceRepository consents;
    private final OtpChallengeIssuer issuer;
    private final OtpChallengeRepository challenges;
    private final DisputeExitChallengeRepository bindings;
    private final SettlementProposalRepository proposals;
    private final OtpCodes otpCodes;
    private final OtpPolicy policy;
    private final OtpEmailPort otpEmailPort;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** Consent + OTP challenge + email (purpose CONCEDE_DISPUTE or SETTLE_DISPUTE); nothing else is recorded. */
    public SignChallengeView initiate(DisputeExitType type, DisputeExitCommands.Initiate command) {
        if ((type == DisputeExitType.SETTLE) != (command.agreedSellerEntitlementAmount() != null)) {
            throw new IllegalArgumentException("agreedSellerEntitlementAmount is required for a settlement only");
        }
        eligibility.requireFreshLogin(command.authTime());
        UserInfo party = users.findUser(command.actorId()).orElseThrow(() ->
                new UnauthorizedContractAccessException("No profile was found for this account."));
        String operation = type == DisputeExitType.CONCEDE ? INITIATE_CONCEDE : INITIATE_SETTLE;

        IssuedOtp issued = transactions.inTransaction(() -> {
            Contract contract = lockContract(command.contractId());
            SignerRole role = partyOf(contract, command.actorId());
            DisputeExitStep.Locked locked = step.lock(contract, command.milestoneId()).orElseThrow(
                    () -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
            IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), operation,
                    command.idempotencyKey(), initiateHash(type, command));
            if (outcome == IdempotencyStore.Outcome.CONFLICT) {
                throw new IdempotencyKeyReusedException();
            }
            if (outcome == IdempotencyStore.Outcome.REPLAY) {
                return null;
            }
            DisputeExitWindow window = DisputeExitWindow.require(locked.window());
            if (type == DisputeExitType.SETTLE) {
                window.requireSettlementAllowed();
                window.requireWithinRange(command.agreedSellerEntitlementAmount());
                proposals.findOpenByMilestoneId(command.milestoneId()).ifPresent(open ->
                        open.requireAcceptableBy(role, command.agreedSellerEntitlementAmount()));
            }
            ConsentEvidence consent = statements.consent(contract, type.action(), role, command.actorId(),
                    command.consentStatementVersion(), command.presentedContentHash(), UUID.randomUUID(), now(),
                    command.idempotencyKey().toString());
            consents.append(consent);
            IssuedOtp otp = issuer.issue(contract, consent, command.authTime());
            bindings.insert(new DisputeExitChallengeRepository.Binding(otp.challenge().otpId(),
                    command.milestoneId(), type, command.agreedSellerEntitlementAmount()));
            idempotencyStore.recordResult(command.actorId(), operation, command.idempotencyKey(),
                    otp.challenge().otpId().toString());
            return otp;
        });
        if (issued == null) {
            return replayedChallenge(command, operation);
        }

        OtpChallenge challenge = issued.challenge();
        String email = party.email();
        if (email == null || email.isBlank()) {
            issuer.expire(challenge.otpId());
            throw new UnauthorizedContractAccessException("Your profile has no email to send the code to.");
        }
        try {
            otpEmailPort.send(new OtpEmail(challenge.otpId().toString(), command.actorId(), email,
                    challenge.contractId(), issued.code(), challenge.signedContentHash(), challenge.expiresAt(),
                    type == DisputeExitType.CONCEDE ? OtpEmail.CONCEDE_DISPUTE : OtpEmail.SETTLE_DISPUTE));
        } catch (OtpEmailNotSentException notSent) {
            issuer.expire(challenge.otpId());
            throw notSent;
        }
        log.info("event=dispute_exit_code_sent contractId={} milestoneId={} type={} party={} otpId={}",
                challenge.contractId(), command.milestoneId(), type, challenge.signerRole(), challenge.otpId());
        return new SignChallengeView(challenge.otpId(), challenge.expiresAt());
    }

    /**
     * Checks the code and acts in the same transaction: a concession ends the dispute; a settlement records the open
     * proposal, or accepts the other party's proposal of the same amount and ends the dispute. A wrong code is counted
     * and committed before the refusal, as in signing. A repeated verify (same Idempotency-Key) is a no-op.
     */
    public void verify(DisputeExitType type, DisputeExitCommands.Verify command) {
        eligibility.requireFreshLogin(command.authTime());
        RuntimeException refusal = transactions.inTransaction(() -> verifyInTx(type, command));
        if (refusal != null) {
            throw refusal;
        }
    }

    private RuntimeException verifyInTx(DisputeExitType type, DisputeExitCommands.Verify command) {
        String operation = type == DisputeExitType.CONCEDE ? VERIFY_CONCEDE : VERIFY_SETTLE;
        Contract contract = lockContract(command.contractId());
        SignerRole role = partyOf(contract, command.actorId());
        String requestHash = CanonicalTerms.sha256(String.join("\n", command.contractId(), command.milestoneId(),
                command.otpId().toString()));
        var previous = idempotencyStore.lookup(command.actorId(), operation, command.idempotencyKey(), requestHash);
        if (previous.isPresent()) {
            if (previous.get() == IdempotencyStore.Outcome.CONFLICT) {
                throw new IdempotencyKeyReusedException();
            }
            return null;
        }
        OtpChallenge challenge = challenges.findByIdForUpdate(command.otpId())
                .filter(c -> c.actionType() == type.action())
                .orElseThrow(() -> new ContractConflictException(VerifyOtpAndSignUseCase.OTP_NOT_FOUND,
                        "No such code for this action."));
        if (!challenge.userId().equals(command.actorId()) || challenge.signerRole() != role) {
            throw new UnauthorizedContractAccessException("This code belongs to another user.");
        }
        if (!challenge.contractId().equals(command.contractId())) {
            throw new ContractConflictException(VerifyOtpAndSignUseCase.OTP_FOR_OTHER_CONTRACT,
                    "This code is for another contract.");
        }
        DisputeExitChallengeRepository.Binding binding = bindings.findByOtpId(challenge.otpId())
                .filter(b -> b.type() == type && b.milestoneId().equals(command.milestoneId()))
                .orElseThrow(() -> new ContractConflictException(VerifyOtpAndSignUseCase.OTP_NOT_FOUND,
                        "This code is for another milestone."));
        Instant now = now();
        challenge.ensureUsable(now, policy.maxAttempts());
        DisputeExitStep.Locked locked = step.lock(contract, command.milestoneId()).orElseThrow(
                () -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
        DisputeExitWindow window = DisputeExitWindow.require(locked.window());
        Optional<SettlementProposal> open = Optional.empty();
        if (type == DisputeExitType.SETTLE) {
            window.requireSettlementAllowed();
            window.requireWithinRange(binding.amount());
            open = proposals.findOpenByMilestoneIdForUpdate(command.milestoneId());
            open.ifPresent(p -> p.requireAcceptableBy(role, binding.amount()));
        }
        if (!otpCodes.matches(command.otpCode(), challenge.otpHash())) {
            int left = policy.maxAttempts() - challenges.recordFailedAttempt(challenge.otpId());
            return left > 0
                    ? new ContractConflictException(VerifyOtpAndSignUseCase.OTP_INVALID,
                            "Wrong code; " + left + " attempt" + (left == 1 ? "" : "s") + " left.")
                    : new ContractConflictException(OtpChallenge.OTP_LOCKED,
                            "Wrong code and no attempts left; request a new code.");
        }
        challenges.markVerified(challenge.otpId(), now);

        String result;
        if (type == DisputeExitType.CONCEDE) {
            result = step.end(locked, window, type, role, null, command.actorId(), now).remedyDecisionId();
        } else if (open.isPresent()) {
            proposals.end(open.get().proposalId(), SettlementProposalStatus.ACCEPTED, now, command.actorId());
            result = step.end(locked, window, type, null, binding.amount(), command.actorId(), now)
                    .remedyDecisionId();
        } else {
            SettlementProposal proposal = SettlementProposal.open(command.contractId(), command.milestoneId(),
                    binding.amount(), role, command.actorId(), now);
            proposals.insert(proposal);
            result = proposal.proposalId();
            log.info("event=settlement_proposed contractId={} milestoneId={} role={} amount={}",
                    command.contractId(), command.milestoneId(), role, binding.amount().wire());
        }
        if (idempotencyStore.claim(command.actorId(), operation, command.idempotencyKey(), requestHash)
                != IdempotencyStore.Outcome.FIRST) {
            throw new IllegalStateException("Idempotency-Key " + command.idempotencyKey() + " claimed concurrently");
        }
        idempotencyStore.recordResult(command.actorId(), operation, command.idempotencyKey(), result);
        return null;
    }

    /** DELETE: the proposer withdraws the open proposal, the other party refuses it; none open → 409. */
    public void withdraw(DisputeExitCommands.Withdraw command) {
        transactions.inTransaction(() -> {
            Contract contract = lockContract(command.contractId());
            SignerRole role = partyOf(contract, command.actorId());
            step.lock(contract, command.milestoneId()).orElseThrow(
                    () -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
            IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), WITHDRAW_SETTLE,
                    command.idempotencyKey(), CanonicalTerms.sha256(command.contractId() + "\n"
                            + command.milestoneId()));
            if (outcome == IdempotencyStore.Outcome.CONFLICT) {
                throw new IdempotencyKeyReusedException();
            }
            if (outcome == IdempotencyStore.Outcome.REPLAY) {
                return;
            }
            SettlementProposal open = proposals.findOpenByMilestoneIdForUpdate(command.milestoneId())
                    .orElseThrow(() -> SettlementProposal.conflict("There is no open settlement proposal."));
            SettlementProposalStatus end = open.endingBy(role);
            proposals.end(open.proposalId(), end, now(), command.actorId());
            idempotencyStore.recordResult(command.actorId(), WITHDRAW_SETTLE, command.idempotencyKey(),
                    open.proposalId());
            log.info("event=settlement_proposal_ended contractId={} milestoneId={} status={} by={}",
                    command.contractId(), command.milestoneId(), end, role);
        });
    }

    /** The 404 and 403 of every dispute exit call, checked before the body is read. */
    public void requirePartyOfMilestone(String contractId, String milestoneId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            if (!step.belongsTo(contract, milestoneId)) {
                throw new MilestoneNotFoundException(contractId, milestoneId);
            }
            partyOf(contract, actorId);
        });
    }

    private Contract lockContract(String contractId) {
        return contracts.findByIdForUpdate(new ContractId(contractId))
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    private static SignerRole partyOf(Contract contract, String actorId) {
        return contract.roleOf(actorId).orElseThrow(() ->
                new UnauthorizedContractAccessException("This user is not a party of this contract."));
    }

    private SignChallengeView replayedChallenge(DisputeExitCommands.Initiate command, String operation) {
        OtpChallenge challenge = idempotencyStore.resultOf(command.actorId(), operation, command.idempotencyKey())
                .map(UUID::fromString)
                .flatMap(challenges::findById)
                .orElseThrow(() -> new IllegalStateException("Idempotency record without challenge"));
        if (!challenge.isUsable(clock.instant(), policy.maxAttempts())) {
            throw new ContractConflictException(InitiateSignUseCase.CHALLENGE_NOT_ACTIVE,
                    "The code for this request is no longer valid; request a new one with a new Idempotency-Key.");
        }
        return new SignChallengeView(challenge.otpId(), challenge.expiresAt());
    }

    private static String initiateHash(DisputeExitType type, DisputeExitCommands.Initiate c) {
        VndAmount amount = c.agreedSellerEntitlementAmount();
        return CanonicalTerms.sha256(String.join("\n", type.name(), c.contractId(), c.milestoneId(),
                amount == null ? "" : amount.wire(), c.consentStatementVersion(), c.presentedContentHash()));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
