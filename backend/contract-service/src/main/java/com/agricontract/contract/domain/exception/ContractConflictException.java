package com.agricontract.contract.domain.exception;

/** A command that is valid in itself but not allowed in the contract's current state (HTTP 409). */
public class ContractConflictException extends RuntimeException {
    public static final String TERMS_FROZEN = "CONTRACT_TERMS_FROZEN";
    public static final String NOT_NEGOTIABLE = "CONTRACT_NOT_NEGOTIABLE";
    public static final String LEGACY_TERMS = "CONTRACT_HAS_NO_COFFEE_TERMS";
    public static final String NOT_SIGNABLE = "CONTRACT_NOT_SIGNABLE";
    public static final String ALREADY_SIGNED = "CONTRACT_ALREADY_SIGNED_BY_ACTOR";
    public static final String CONSENT_STATEMENT_OUTDATED = "CONSENT_STATEMENT_OUTDATED";
    public static final String PRESENTED_CONTENT_STALE = "PRESENTED_CONTENT_HASH_MISMATCH";
    public static final String TERMS_CHANGED_SINCE_CODE = "OTP_TERMS_CHANGED";
    public static final String COUNTERPARTY_NOT_ELIGIBLE = "COUNTERPARTY_NOT_ELIGIBLE";
    public static final String NOT_ACTIVATABLE = "CONTRACT_NOT_ACTIVATABLE";
    /** RetryDepositLock on a contract whose deposit lock has not failed (no escrow.deposit_lock_failed, D42). */
    public static final String ACTIVATION_NOT_FAILED = "ACTIVATION_NOT_FAILED";
    /** D42: cancel request outside SIGNED-with-a-failed-lock, or after the refund started. */
    public static final String NOT_CANCELLABLE = "CONTRACT_NOT_CANCELLABLE";
    /** D42: this party already has a standing cancel request. */
    public static final String CANCEL_ALREADY_REQUESTED = "CANCEL_ALREADY_REQUESTED";
    /** D42: withdraw without a standing request of the caller. */
    public static final String NO_CANCEL_REQUEST = "NO_CANCEL_REQUEST";
    /** #233: withdraw after the second signature (SIGNED or later) or on an already ended offer. */
    public static final String NOT_WITHDRAWABLE = "CONTRACT_NOT_WITHDRAWABLE";
    /** F18 (#232): the second signature on terms whose total differs from the stored reserved quantity. */
    public static final String RESERVED_QUANTITY_MISMATCH = "RESERVED_QUANTITY_MISMATCH";
    /** D67: the second signature when a milestone's funding date (delivery date minus the lead) is already past. */
    public static final String DELIVERY_SCHEDULE_TOO_SOON = "DELIVERY_SCHEDULE_TOO_SOON";

    /** D71-D75: the contract is not ACTIVE, or the milestone is not at the step this action needs. */
    public static final String MILESTONE_ACTION_NOT_ALLOWED = "MILESTONE_ACTION_NOT_ALLOWED";
    /** D75: the seller's correction window has passed, or the buyer has recorded receipt. */
    public static final String WEIGH_CORRECTION_CLOSED = "WEIGH_CORRECTION_CLOSED";
    /** D71: a named file is still being scanned. */
    public static final String EVIDENCE_NOT_READY = "EVIDENCE_NOT_READY";
    /** D71: a named file failed the scan. */
    public static final String EVIDENCE_REJECTED = "EVIDENCE_REJECTED";
    /** D74: the presentedContentHash of an operational consent is not the server's hash of the action content. */
    public static final String CONSENT_CONTENT_MISMATCH = "CONSENT_CONTENT_MISMATCH";

    private final String code;

    public ContractConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
