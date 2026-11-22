package com.agricontract.contract.domain.model.milestone;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The buyer's receipt of a milestone (D76, step 1): the unloaded weight, the files behind it and when it was recorded
 * (receivedAt starts the buyerConfirmWindowDays clock). There is no correction, so there is only one.
 */
public record BuyerReceipt(BigDecimal receivedWeight, String evidenceFileId, String certificateFileId,
                           Instant receivedAt) {
    public BuyerReceipt {
        SellerWeight.requireValid(receivedWeight);
        Objects.requireNonNull(evidenceFileId, "evidenceFileId");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
