package com.agricontract.bank.infrastructure.web;

import com.agricontract.bank.domain.model.LedgerEntry;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/** One item of LedgerEntryList. All keys are always written, nulls included. */
public record LedgerEntryResponse(
        String entryId,
        String sourceEventId,
        String contractId,
        String milestoneId,
        String userId,
        String entryType,
        String fundType,
        String remedyDecisionId,
        String remedyLegId,
        String amount,
        String createdAt
) {

    static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(e.getEntryId(), e.getSourceEventId(), e.getContractId(), e.getMilestoneId(),
                e.getUserId(), e.getEntryType().name(), e.getFundType().name(), e.getRemedyDecisionId(),
                e.getRemedyLegId(), e.getAmount().amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                DateTimeFormatter.ISO_INSTANT.format(e.getCreatedAt()));
    }
}
