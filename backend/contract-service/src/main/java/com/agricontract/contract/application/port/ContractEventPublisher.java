package com.agricontract.contract.application.port;

import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.activation.ActivationRecovery;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;

import java.time.Instant;
import java.util.List;

/**
 * Canonical contract events (golden-flow-events.yaml), written to the event outbox. Must be called inside the
 * transaction that makes the change, so the event exists exactly when the change commits.
 */
public interface ContractEventPublisher {

    /** contract.created, once, by the create that persisted the OFFERED contract after its reservation (#232). */
    void contractCreated(Contract contract, Instant createdAt);

    /** contract.signed, once, by the verify that made the contract SIGNED; signedAt is the second signature. */
    void contractSigned(Contract contract, Instant signedAt);

    /**
     * notification.contract_anchor_requested (#360): the "contract signed" email to both parties, with the signed
     * terms and their hash. Written next to contract.signed, which notification-service does not consume.
     */
    void contractAnchorRequested(Contract contract, Instant signedAt);

    /**
     * contract.activated (#103): product-service's hard inventory commit. correlationId continues the activation
     * chain; causationId is the escrow.deposit_locked that activated the contract.
     */
    void contractActivated(Contract contract, Instant activatedAt, String correlationId, String causationId);

    /**
     * notification.contract_activation_failed_requested (#234, D42): both parties learn that the deposits could not be
     * locked, what they can do next and from when one party's cancel request is enough.
     */
    void contractActivationFailedRequested(Contract contract, ActivationRecovery recovery, String buyerDepositState,
                                           String sellerDepositState, String correlationId, String causationId);

    /** contract.deposit_lock_retry_requested (D42): OPERATOR asks escrow to lock the failed legs again. */
    void depositLockRetryRequested(Contract contract, String operatorUserId, Instant requestedAt);

    /**
     * contract.activation_refund_requested (D42), with SIGNED → ACTIVATION_REFUND_PENDING: escrow returns the locked
     * deposits. trigger is BOTH_PARTIES or ONE_PARTY_AFTER_WINDOW.
     */
    void activationRefundRequested(Contract contract, List<SignerRole> requestedBy, String trigger, Instant at);

    /** notification.activation_cancel_requested (D42): the other party learns of the request and of the window. */
    void activationCancelRequested(Contract contract, SignerRole requestedBy, Instant oneSidedCancelAllowedFrom,
                                   Instant at);

    /** contract.activation_failed: terminal ACTIVATION_FAILED, product-service releases the reservation. */
    void contractActivationFailed(Contract contract, Instant failedAt, String correlationId, String causationId);

    /** contract.withdrawn (#233): product-service releases the reservation of an offer withdrawn before signing. */
    void contractWithdrawn(Contract contract, Instant withdrawnAt);

    /**
     * contract.terminated with terminationType WITHDRAW_OFFER (#233): lifecycle for audit and analytics only, never
     * money. Published at once, unlike other terminations: nothing is locked before SIGNED, so lock = 0 by
     * construction.
     */
    void offerWithdrawnTerminated(Contract contract, SignerRole requestedBy, Instant withdrawnAt);

    /** notification.contract_terminated_requested, WITHDRAW_OFFER (#233): emails the other party. */
    void offerWithdrawnNoticeRequested(Contract contract, SignerRole requestedBy, Instant withdrawnAt);

    /**
     * milestone.funding_requested (D63): escrow locks exactly the milestone's batchAmount from the buyer. Written once
     * per milestone, by the activation or by the daily schedule (D64).
     */
    void milestoneFundingRequested(Contract contract, Milestone milestone, String correlationId, String causationId);

    /**
     * milestone.seller_weighed (D71-D75): written for every declaration, the first and each correction, with its
     * declarationNumber. The highest number is the declaration in force.
     */
    void milestoneSellerWeighed(Contract contract, Milestone milestone, SellerWeighDeclaration declaration);

    /** milestone.buyer_received (D76): the milestone is BUYER_RECEIVED and holds the receipt; starts the confirm window. */
    void milestoneBuyerReceived(Contract contract, Milestone milestone);

    /**
     * milestone.settled (D77): the milestone is SETTLED and holds its settlement; escrow releases actualAmount to the
     * seller and returns the rest of lockedAmount to the buyer.
     */
    void milestoneSettled(Contract contract, Milestone milestone);

    /** milestone.buyer_confirmed (D78): confirmedBy is BUYER, or SYSTEM_TIMEOUT when the confirm window passed. */
    void milestoneBuyerConfirmed(Contract contract, Milestone milestone, String confirmedBy);

    /** milestone.flagged (D80, D82): the buyer flagged the milestone; an allegation, no money or reputation effect. */
    void milestoneFlagged(Contract contract, Milestone milestone);

    /** breach.reported (D80): the allegation opened by the contest step; audit only, never money or reputation. */
    void breachReported(BreachCase breachCase);

    /**
     * inspection.level1_5_requested (D80, D82; D77 for MANDATORY_INSPECTION). initiatedByActorId is the seller who
     * contested, or null when the system asks (a silent QUALITY flag, a mandatory inspection).
     */
    void inspectionLevel15Requested(Contract contract, Milestone milestone, InspectionRequestReason reason,
                                    String initiatedByActorId, Instant at);

    /**
     * inspection.level1_5_cancelled (D57, D92): the dispute ended by a concession or an agreed settlement while a Level
     * 1.5 request was pending; reason DISPUTE_CONCEDED or DISPUTE_SETTLED.
     */
    void inspectionLevel15Cancelled(Contract contract, Milestone milestone, String reason, Instant at);

    /**
     * milestone.dispute_resolved (D82): the QUANTITY flag settled on the buyer's weight because the seller was silent;
     * resolutionFavors BUYER.
     */
    default void milestoneDisputeResolvedForBuyer(Contract contract, Milestone milestone) {
        milestoneDisputeResolved(contract, milestone, SignerRole.BUYER);
    }

    /**
     * milestone.dispute_resolved (D82, #137): the dispute ended with the milestone SETTLED; resolutionFavors is the
     * prevailing role. The milestone must be SETTLED.
     */
    void milestoneDisputeResolved(Contract contract, Milestone milestone, SignerRole resolutionFavors);

    /**
     * remedy.finalized (#137): the sole trigger of the remedy money legs and of the reputation lock. Written when the
     * RemedyDecision becomes FINALIZED, in that transaction, once.
     */
    void remedyFinalized(Contract contract, RemedyDecision decision, Instant at);
}
