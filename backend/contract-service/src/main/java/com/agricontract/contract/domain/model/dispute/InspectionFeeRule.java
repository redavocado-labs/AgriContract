package com.agricontract.contract.domain.model.dispute;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.inspection.Level15Assignment;
import com.agricontract.contract.domain.model.inspection.Level15Request;
import com.agricontract.contract.domain.model.remedy.InspectionFeeParty;

import java.time.Instant;
import java.util.Objects;

/**
 * Who bears the Level 1.5 inspection fee when the parties end a dispute themselves (D57, D92), in this order:
 * <ol>
 *   <li>the first report of the pending round came back INCONCLUSIVE (the inspector did the work): SPLIT for a
 *       settlement, the conceding party for a concession;</li>
 *   <li>else, an inspector is assigned for the current request (the latest assignment, made at or after the open
 *       request) and its freeCancelUntil has passed: the conceding party for a concession, the party that requested
 *       the inspection for a settlement;</li>
 *   <li>else (no assignment yet, or still inside the free window): no fee (null).</li>
 * </ol>
 */
public final class InspectionFeeRule {
    private InspectionFeeRule() {
    }

    /**
     * @param conceder      the conceding party, null for a settlement
     * @param openRequest   the milestone's latest open Level 1.5 request, null when none is open
     * @param assignment    the milestone's latest assignment, null when none was received
     * @param requesterRole the role of the party that requested the open inspection (SELLER when the system did)
     */
    public static InspectionFeeParty forExit(DisputeExitType type, SignerRole conceder, DisputeExitWindow window,
                                             Level15Request openRequest, Level15Assignment assignment,
                                             SignerRole requesterRole, Instant now) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(now, "now");
        if (type == DisputeExitType.CONCEDE) {
            Objects.requireNonNull(conceder, "conceder");
        }
        if (window.pendingRoundInconclusive()) {
            return type == DisputeExitType.SETTLE ? InspectionFeeParty.SPLIT : InspectionFeeParty.of(conceder);
        }
        boolean chargeable = openRequest != null && assignment != null
                && !assignment.assignedAt().isBefore(openRequest.requestedAt())
                && !now.isBefore(assignment.freeCancelUntil());
        if (!chargeable) {
            return null;
        }
        return InspectionFeeParty.of(type == DisputeExitType.CONCEDE ? conceder
                : requesterRole == null ? SignerRole.SELLER : requesterRole);
    }
}
