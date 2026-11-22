package com.agricontract.contract.infrastructure.consent;

import com.agricontract.contract.domain.model.consent.ConsentActionType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Which version of each checkbox statement is in force. The text lives in
 * classpath:consent-statement/{code}-{version}.txt; the hash is computed from that file at startup.
 * milestoneActionVersions: the operational-tier milestone statements (D74), by action, e.g. DECLARE_SELLER_WEIGHT.
 */
@ConfigurationProperties(prefix = "contract.consent")
public record ConsentStatementProperties(String signContractVersion, String cancelActivationVersion,
                                         Map<ConsentActionType, String> milestoneActionVersions) {
    public ConsentStatementProperties {
        milestoneActionVersions = milestoneActionVersions == null ? Map.of() : Map.copyOf(milestoneActionVersions);
    }
}
