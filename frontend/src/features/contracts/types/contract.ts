export type ParticipantRole = "BUYER" | "SELLER"
export type Fields = Record<string, unknown>

/** ContractResponse.status of golden-flow-api.yaml. */
export type ContractStatus =
  | "OFFERED"
  | "NEGOTIATING"
  | "SIGNED"
  | "ACTIVE"
  | "REPLACEMENT_PENDING"
  | "SUPERSEDE_REFUND_PENDING"
  | "ACTIVATION_REFUND_PENDING"
  | "SETTLED"
  | "TERMINATED"
  | "WITHDRAWN"
  | "SUPERSEDED"
  | "ACTIVATION_FAILED"

export type MilestoneStatus =
  | "CREATED"
  | "IN_PROGRESS"
  | "SELLER_WEIGHED"
  | "BUYER_RECEIVED"
  | "AWAITING_SELLER_RESPONSE"
  | "CONTESTED"
  | "FORCE_MAJEURE_PENDING_REVIEW"
  | "SETTLED"

export type FundingStatus =
  "NOT_REQUESTED" | "FUNDING_PENDING" | "LOCKED" | "FUNDING_FAILED"

/** MilestoneResponse of golden-flow-api.yaml. Money stays a decimal string. */
export interface Milestone {
  milestoneId: string
  milestoneIndex: number
  status: MilestoneStatus
  fundingStatus: FundingStatus
  fundingScheduledDate: string
  committedQuantity: number
  batchAmount: string | null
  effectiveUnitPrice: string
  acceptedPriceAdjustment: Fields | null
  expectedDeliveryDate: string
  graceDays: number
  fundingDelayBusinessDays: number
  effectiveDeliveryDeadline: string
}

export interface ActivationRecovery {
  depositLockFailedAt: string
  oneSidedCancelAllowedFrom: string
  cancelRequestedBy: ParticipantRole[]
}

export interface Contract {
  id: string
  listingId: string
  buyerId: string
  sellerId: string
  /** Phase 1 rows only; ContractResponse carries no organisation names. */
  buyerName?: string
  sellerName?: string
  productName?: string
  status: string
  /** "legacy" = Phase 1 row answered with its old body; read only. */
  generation: "legacy" | "coffee"
  terms: Fields
  hash: string | null
  reservedQuantity?: string
  milestones: Milestone[]
  activationRecovery?: ActivationRecovery
  completion?: { status: "PENDING_MONEY" }
}
export interface ContractPage {
  content: Contract[]
  page: number
  size: number
  totalPages: number
  totalElements: number
}
/** A server-owned checkbox statement, shown word for word. */
export interface Consent {
  action: string
  version: string
  text: string
  contractId: string
  contentHash: string
}
export interface Challenge {
  otpId: string
  expiresAt: string
}
