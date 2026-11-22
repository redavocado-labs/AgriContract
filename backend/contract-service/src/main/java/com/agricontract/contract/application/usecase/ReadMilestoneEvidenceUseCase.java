package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.exception.EvidenceFileNotFoundException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.MilestoneReadAccess;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * readMilestoneEvidence (D84): the other party and the Arbitrator see the photos through contract-service. Party
 * (403) → file recorded for this milestone (404, never a probe into file-service) → READY (409) → bytes from the
 * internal file-service GET, read outside the transaction.
 */
@Service
@RequiredArgsConstructor
public class ReadMilestoneEvidenceUseCase {
    static final Set<String> STAFF = Set.of("ARBITRATOR");

    private final MilestoneReadAccess access;
    private final MilestoneEvidenceRepository evidence;
    private final EvidenceFileStore files;
    private final TransactionRunner transactions;

    public EvidenceFileStore.FileContent execute(String contractId, String milestoneId, String fileId,
                                                 String actorId, Set<String> roles) {
        transactions.inTransaction(() -> {
            access.require(contractId, milestoneId, actorId, roles, STAFF);
            MilestoneEvidenceFile file = evidence.findById(fileId)
                    .filter(f -> f.milestoneId().equals(milestoneId))
                    .orElseThrow(() -> new EvidenceFileNotFoundException(milestoneId, fileId));
            MilestoneEvidenceFile.requireReady(fileId, null, Map.of(fileId, file));
        });
        return files.read(fileId);
    }
}
