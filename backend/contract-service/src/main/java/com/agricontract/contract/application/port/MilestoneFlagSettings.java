package com.agricontract.contract.application.port;

/**
 * Service-wide flag settings (application.yml, not ContractTerms). sellerResponseWindowDays (D80, D82): the business
 * days the seller has to answer a flag, counted from flaggedAt.
 */
public record MilestoneFlagSettings(int sellerResponseWindowDays) {
    public MilestoneFlagSettings {
        if (sellerResponseWindowDays < 1) {
            throw new IllegalArgumentException("sellerResponseWindowDays must be at least 1");
        }
    }
}
