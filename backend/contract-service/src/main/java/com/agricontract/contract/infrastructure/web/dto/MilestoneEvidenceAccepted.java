package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;

/** MilestoneEvidenceAccepted (golden-flow-api.yaml): the file and its scan status as contract-service knows it. */
public record MilestoneEvidenceAccepted(String fileId, String status) {
    public static MilestoneEvidenceAccepted from(MilestoneEvidenceCommands.Uploaded uploaded) {
        return new MilestoneEvidenceAccepted(uploaded.fileId(), uploaded.status().name());
    }
}
