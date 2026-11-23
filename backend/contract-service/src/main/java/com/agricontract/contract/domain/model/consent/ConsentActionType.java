package com.agricontract.contract.domain.model.consent;

/** The behaviour a checkbox statement is about (contract-document-consent design §4). */
public enum ConsentActionType {
    SIGN_CONTRACT,
    /** D42: a party asks to stop a SIGNED contract whose deposit lock failed. Same high-risk tier as signing. */
    CANCEL_ACTIVATION,
    /** D74: the seller declares (or corrects, D75) the loaded weight of a milestone. Operational tier, no OTP. */
    DECLARE_SELLER_WEIGHT,
    /** D76: the buyer records the unloaded weight of a SELLER_WEIGHED milestone. Operational tier, no OTP. */
    RECORD_BUYER_RECEIPT,
    /** D77: the buyer confirms a clean receipt and agrees to settle the milestone. Operational tier, no OTP. */
    CONFIRM_CLEAN_RECEIPT,
    /** D80: the buyer flags a BUYER_RECEIVED milestone (not yet a breach). Operational tier, no OTP. */
    FLAG_MILESTONE_ISSUE,
    /** D80: the seller contests the buyer's flag and asks for an independent inspection. Operational tier, no OTP. */
    CONTEST_MILESTONE_FLAG,
    /** D57, D92: a party accepts the other party's position in a milestone dispute. High-risk tier: fresh login + OTP. */
    CONCEDE_DISPUTE,
    /** D57, D92: a party proposes or accepts an agreed seller entitlement for a disputed milestone. Fresh login + OTP. */
    SETTLE_DISPUTE
}
