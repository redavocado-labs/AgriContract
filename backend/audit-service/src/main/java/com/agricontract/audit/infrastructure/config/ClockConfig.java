package com.agricontract.audit.infrastructure.config;

import com.agricontract.audit.application.port.TransactionRunner;
import com.agricontract.audit.application.usecase.IngestAuditEventUseCase;
import com.agricontract.audit.application.usecase.IngestAuditEventUseCaseImpl;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Clock and use case wiring: the application layer carries no Spring annotation. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IngestAuditEventUseCase ingestAuditEventUseCase(AuditRecordRepository records, TransactionRunner transactions,
                                                    Clock clock) {
        return new IngestAuditEventUseCaseImpl(records, transactions, clock);
    }
}
