package com.agricontract.audit.application.usecase;

import com.agricontract.audit.application.dto.AuditIngestCommand;

public interface IngestAuditEventUseCase {

    void execute(AuditIngestCommand command);
}
