package com.agricontract.audit.application.usecase;

import com.agricontract.audit.application.dto.ChainVerificationResult;
import com.agricontract.audit.domain.model.SubjectType;

public interface VerifyAuditChainUseCase {

    /** Recomputes every record hash and checks each prevHashGlobal against the record before it. */
    ChainVerificationResult verifyGlobal();

    /** Recomputes this subject's record hashes and checks each prevHashSubject against the subject's previous one. */
    ChainVerificationResult verifySubject(SubjectType subjectType, String subjectId);
}
