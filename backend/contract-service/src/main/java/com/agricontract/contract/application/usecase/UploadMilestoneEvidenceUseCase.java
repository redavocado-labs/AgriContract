package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.FileRejectedException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.MilestoneDeliverySettings;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * uploadMilestoneEvidence (D71, design §3.2.1). Checks, in this order and before a byte is read: the caller is a party
 * of the contract (403), the milestone is the contract's (404), the caller's turn on an ACTIVE contract (409), then
 * the type and size (400/413). A repeat of the caller's Idempotency-Key for this milestone answers with the file's
 * current status, whatever the milestone's state now. The bytes are streamed to file-service outside any transaction;
 * the turn is checked again under the contract and milestone locks before the file is recorded, so a file that lost
 * the race is never recorded (file-service cleans it as an orphan).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadMilestoneEvidenceUseCase {
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");

    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final MilestoneEvidenceRepository evidence;
    private final EvidenceFileStore files;
    private final MilestoneDeliverySettings settings;
    private final TransactionRunner transactions;
    private final Clock clock;

    public MilestoneEvidenceCommands.Uploaded execute(MilestoneEvidenceCommands.Upload command, InputStream body) {
        String key = command.idempotencyKey().toString();
        Optional<MilestoneEvidenceCommands.Uploaded> replay = transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(command.contractId()))
                    .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
            SignerRole role = partyOf(contract, command.actorId());
            Milestone milestone = milestones.findById(command.milestoneId())
                    .filter(m -> m.contractId().equals(command.contractId()))
                    .orElseThrow(() -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
            Optional<MilestoneEvidenceCommands.Uploaded> previous = previousUpload(command, key);
            if (previous.isEmpty()) {
                requireTurn(contract, milestone, role, now());
            }
            return previous;
        });
        if (replay.isPresent()) {
            return replay.get();
        }
        String contentType = requireAcceptable(command.contentType(), command.contentLength());

        EvidenceFileStore.StoredFile stored = files.storeOnBehalfOf(command.actorId(), key, contentType,
                command.contentLength(), body);

        return transactions.inTransaction(() -> {
            // Contract lock first, as every contract-service write does, then the milestone.
            Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                    .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
            SignerRole role = partyOf(contract, command.actorId());
            Milestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                    .orElseThrow(() -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
            Optional<MilestoneEvidenceCommands.Uploaded> previous = previousUpload(command, key);
            if (previous.isPresent()) {
                return previous.get();
            }
            Instant now = now();
            requireTurn(contract, milestone, role, now);
            evidence.insert(new MilestoneEvidenceFile(stored.fileId(), milestone.milestoneId(),
                    command.contractId(), command.actorId(), role, now, EvidenceScanStatus.PROCESSING), key);
            EvidenceScanStatus status = evidence.findById(stored.fileId()).orElseThrow().scanStatus();
            log.info("event=milestone_evidence_uploaded contractId={} milestoneId={} fileId={} party={} status={}",
                    command.contractId(), milestone.milestoneId(), stored.fileId(), role, status);
            return new MilestoneEvidenceCommands.Uploaded(stored.fileId(), status);
        });
    }

    /** The caller's earlier upload with this key: its status for this milestone, a refusal for another one. */
    private Optional<MilestoneEvidenceCommands.Uploaded> previousUpload(MilestoneEvidenceCommands.Upload command,
                                                                        String key) {
        return evidence.findByUploaderAndKey(command.actorId(), key).map(file -> {
            if (!file.milestoneId().equals(command.milestoneId())) {
                throw new IdempotencyKeyReusedException();
            }
            return new MilestoneEvidenceCommands.Uploaded(file.fileId(), file.scanStatus());
        });
    }

    private void requireTurn(Contract contract, Milestone milestone, SignerRole role, Instant now) {
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The contract is " + contract.getStatus() + "; evidence can only be uploaded while it is ACTIVE.");
        }
        milestone.requireEvidenceUploadTurn(role, now, settings.weighCorrectionWindow());
    }

    private static SignerRole partyOf(Contract contract, String actorId) {
        return contract.roleOf(actorId).orElseThrow(() ->
                new UnauthorizedContractAccessException("This user is not a party of this contract."));
    }

    /** The storeOnBehalfOf rules (D60), checked before the body is read. Returns the bare media type. */
    static String requireAcceptable(String contentType, long contentLength) {
        String type = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(type)) {
            throw new FileRejectedException(400, FileRejectedException.VALIDATION_ERROR,
                    "Only JPEG, PNG or PDF files can be uploaded.");
        }
        if (contentLength < 1) {
            throw new FileRejectedException(400, FileRejectedException.VALIDATION_ERROR,
                    "Content-Length is required and the file cannot be empty.");
        }
        if (contentLength > MAX_BYTES) {
            throw new FileRejectedException(413, FileRejectedException.FILE_TOO_LARGE,
                    "The file is larger than 10 MiB.");
        }
        return type;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
