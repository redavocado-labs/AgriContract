/**
 * Dev-only BF-04 fixtures, shaped exactly like golden-flow-api.yaml + docs PR
 * #552 (D84): ContractResponse, MilestoneDetailResponse, BreachCase(Page).
 * Loaded only when VITE_MOCK_API=bf04; never part of a production bundle.
 * Instants are relative to page load so every server deadline looks live.
 */
const now = Date.now()
const at = (minutes: number) => new Date(now + minutes * 60_000).toISOString()
const day = (days: number) => {
  const d = new Date(now + days * 86_400_000)
  return d.toISOString().slice(0, 10)
}

export const BUYER_ID = "b1a2c3d4-0000-4000-8000-00000000b001"
export const SELLER_ID = "5e11e000-0000-4000-8000-00000000c001"
export const ARBITRATOR_ID = "a7b17000-0000-4000-8000-00000000a001"
export const OPERATOR_ID = "0be7a700-0000-4000-8000-00000000d001"
export const CONTRACT_ID = "c0ffee00-2026-4142-8000-000000000142"
export const CONTRACT_2_ID = "c0ffee38-2026-4138-8000-000000000138"

const mid = (n: number) =>
  `a1100000-0000-4000-8000-0000000000${String(n).padStart(2, "0")}`
const file = (n: number) =>
  `f11e0000-0000-4000-8000-0000000000${String(n).padStart(2, "0")}`
export const BREACH_1 = "bc000000-0000-4000-8000-000000000074"
export const BREACH_2 = "bc000000-0000-4000-8000-000000000071"

const price = "95000.00"
type Status =
  | "CREATED"
  | "IN_PROGRESS"
  | "SELLER_WEIGHED"
  | "BUYER_RECEIVED"
  | "AWAITING_SELLER_RESPONSE"
  | "CONTESTED"
  | "FORCE_MAJEURE_PENDING_REVIEW"
  | "SETTLED"

function summary(
  n: number,
  status: Status,
  extra: Record<string, unknown> = {}
) {
  const funded = status !== "CREATED"
  return {
    milestoneId: mid(n),
    milestoneIndex: n,
    status,
    fundingStatus: funded ? "LOCKED" : "FUNDING_FAILED",
    fundingScheduledDate: day(n * 3 - 30),
    committedQuantity: 10000,
    batchAmount: "950000000.00",
    effectiveUnitPrice: price,
    acceptedPriceAdjustment: null,
    expectedDeliveryDate: day(n * 3 - 20),
    graceDays: 2,
    fundingDelayBusinessDays: 0,
    effectiveDeliveryDeadline: day(n * 3 - 18),
    ...extra,
  }
}

const evidence = (
  n: number,
  role: "BUYER" | "SELLER",
  minutes: number,
  status = "READY"
) => ({
  fileId: file(n),
  uploadedByRole: role,
  status,
  uploadedAt: at(minutes),
})
const declaration = (
  photo: number,
  weight: number,
  minutes: number,
  number = 1
) => ({
  declarationNumber: number,
  sellerDeclaredWeight: weight,
  sellerEvidenceFileId: file(photo),
  deliveryCertificateFileId: null,
  declaredAt: at(minutes),
  correctionWindowClosesAt: at(minutes + 60),
})
const receipt = (
  photo: number,
  weight: number,
  minutes: number,
  confirmIn: number
) => ({
  buyerReceivedWeight: weight,
  buyerEvidenceFileId: file(photo),
  deliveryCertificateFileId: null,
  receivedAt: at(minutes),
  confirmWindowClosesAt: at(confirmIn),
})

export type Detail = {
  contractId: string
  milestone: ReturnType<typeof summary>
  sellerDeclaration: ReturnType<typeof declaration> | null
  buyerReceipt: ReturnType<typeof receipt> | null
  flag: {
    issueType: "QUANTITY" | "QUALITY"
    flaggedAt: string
    sellerResponseWindowClosesAt: string
    contestedAt: string | null
    contestedBy: "SELLER" | "SYSTEM" | null
  } | null
  evidence: ReturnType<typeof evidence>[]
  settlement: {
    path: string
    billableQuantityKg: number | null
    sellerAmount: string
    buyerRefundAmount: string
    settledAt: string
  } | null
  breachCaseId: string | null
}

/** One contract, one milestone per BF-04 state. */
export function initialDetails(): Detail[] {
  return [
    {
      contractId: CONTRACT_ID,
      milestone: summary(1, "SETTLED"),
      sellerDeclaration: declaration(1, 9980, -60 * 24 * 12),
      buyerReceipt: receipt(2, 9900, -60 * 24 * 11, -60 * 24 * 8),
      flag: null,
      evidence: [
        evidence(1, "SELLER", -60 * 24 * 12),
        evidence(2, "BUYER", -60 * 24 * 11),
      ],
      settlement: {
        path: "CLEAN_CONFIRMED",
        billableQuantityKg: 9980,
        sellerAmount: "948100000.00",
        buyerRefundAmount: "1900000.00",
        settledAt: at(-60 * 24 * 10),
      },
      breachCaseId: null,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(2, "SETTLED"),
      sellerDeclaration: declaration(3, 9900, -60 * 24 * 9),
      buyerReceipt: receipt(4, 9420, -60 * 24 * 8, -60 * 24 * 6),
      flag: {
        issueType: "QUANTITY",
        flaggedAt: at(-60 * 24 * 8 + 120),
        sellerResponseWindowClosesAt: at(-60 * 24 * 6),
        contestedAt: at(-60 * 24 * 7),
        contestedBy: "SELLER",
      },
      evidence: [
        evidence(3, "SELLER", -60 * 24 * 9),
        evidence(4, "BUYER", -60 * 24 * 8),
      ],
      settlement: {
        path: "INSPECTION_RESOLVED",
        billableQuantityKg: 9650,
        sellerAmount: "916750000.00",
        buyerRefundAmount: "33250000.00",
        settledAt: at(-60 * 24 * 2),
      },
      breachCaseId: BREACH_2,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(3, "CONTESTED"),
      sellerDeclaration: declaration(5, 9700.5, -60 * 24 * 6),
      buyerReceipt: receipt(6, 9650.25, -60 * 24 * 5, -60 * 24 * 3),
      flag: {
        issueType: "QUALITY",
        flaggedAt: at(-60 * 24 * 5 + 90),
        sellerResponseWindowClosesAt: at(-60 * 24 * 3),
        contestedAt: at(-60 * 24 * 4),
        contestedBy: "SELLER",
      },
      evidence: [
        evidence(5, "SELLER", -60 * 24 * 6),
        evidence(6, "BUYER", -60 * 24 * 5),
      ],
      settlement: null,
      breachCaseId: BREACH_1,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(4, "AWAITING_SELLER_RESPONSE"),
      sellerDeclaration: declaration(7, 10020, -60 * 26),
      buyerReceipt: receipt(8, 9610, -60 * 20, 60 * 24 * 2),
      flag: {
        issueType: "QUANTITY",
        flaggedAt: at(-60 * 3),
        sellerResponseWindowClosesAt: at(60 * 30),
        contestedAt: null,
        contestedBy: null,
      },
      evidence: [
        evidence(7, "SELLER", -60 * 26),
        evidence(8, "BUYER", -60 * 20),
      ],
      settlement: null,
      breachCaseId: null,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(5, "BUYER_RECEIVED"),
      sellerDeclaration: declaration(9, 9680, -60 * 30),
      buyerReceipt: receipt(10, 9550, -60 * 4, 60 * 24 * 2 + 60 * 5),
      flag: null,
      evidence: [
        evidence(9, "SELLER", -60 * 30),
        evidence(10, "BUYER", -60 * 4),
      ],
      settlement: null,
      breachCaseId: null,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(6, "SELLER_WEIGHED"),
      sellerDeclaration: declaration(11, 9875.5, -18),
      buyerReceipt: null,
      flag: null,
      evidence: [evidence(11, "SELLER", -19)],
      settlement: null,
      breachCaseId: null,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(7, "IN_PROGRESS", {
        effectiveDeliveryDeadline: day(6),
        expectedDeliveryDate: day(4),
      }),
      sellerDeclaration: null,
      buyerReceipt: null,
      flag: null,
      evidence: [],
      settlement: null,
      breachCaseId: null,
    },
    {
      contractId: CONTRACT_ID,
      milestone: summary(8, "CREATED", {
        batchAmount: "950000000.00",
        fundingScheduledDate: day(-1),
        expectedDeliveryDate: day(9),
        effectiveDeliveryDeadline: day(11),
      }),
      sellerDeclaration: null,
      buyerReceipt: null,
      flag: null,
      evidence: [],
      settlement: null,
      breachCaseId: null,
    },
    {
      contractId: CONTRACT_2_ID,
      milestone: summary(1, "CONTESTED"),
      sellerDeclaration: declaration(12, 5000, -60 * 24 * 7),
      buyerReceipt: receipt(13, 4710, -60 * 24 * 6, -60 * 24 * 4),
      flag: {
        issueType: "QUANTITY",
        flaggedAt: at(-60 * 24 * 6 + 60),
        sellerResponseWindowClosesAt: at(-60 * 24 * 4),
        contestedAt: at(-60 * 24 * 5),
        contestedBy: "SELLER",
      },
      evidence: [
        evidence(12, "SELLER", -60 * 24 * 7),
        evidence(13, "BUYER", -60 * 24 * 6),
      ],
      settlement: null,
      breachCaseId: "bc000000-0000-4000-8000-000000000069",
    },
  ]
}

export function initialBreachCases() {
  const base: Record<string, unknown> & {
    resolvedAt: string | null
    status: string
    decisionSource: string | null
    finalBreachingRole: string | null
  } = {
    requestedBy: "BUYER",
    allegedBreachingRole: "SELLER",
    severity: "MINOR",
    finalBreachingRole: null,
    decisionSource: null,
    status: "UNDER_REVIEW",
    resolvedAt: null,
    contestStatus: "NOT_APPLICABLE",
    contestDeadline: null,
  }
  return [
    {
      ...base,
      breachCaseId: BREACH_1,
      contractId: CONTRACT_ID,
      milestoneId: mid(3),
      breachReasonCode: "QUALITY_BELOW_COMMITTED",
      evidenceFileIds: [file(6)],
      status: "REPORTED",
      reportedAt: at(-60 * 24 * 4),
      computedResolution: {
        inspectionReportId: "1e500000-0000-4000-8000-000000000015",
        issueType: "QUALITY",
        acceptedQuantityKg: 9640,
        qualityDisposition: "NON_CONFORMING",
        finalBreachingRole: "SELLER",
        decisionSource: "INSPECTOR_L1_5",
        sellerEntitlementAmount: "0.00",
        buyerRefundAmount: "950000000.00",
        prevailingRole: "BUYER",
      },
    },
    {
      ...base,
      breachCaseId: "bc000000-0000-4000-8000-000000000069",
      contractId: CONTRACT_2_ID,
      milestoneId: mid(1),
      breachReasonCode: "SHORTFALL",
      evidenceFileIds: [file(13)],
      reportedAt: at(-60 * 24 * 5),
      computedResolution: {
        inspectionReportId: "1e500000-0000-4000-8000-000000000016",
        issueType: "QUANTITY",
        acceptedQuantityKg: 4760,
        qualityDisposition: null,
        finalBreachingRole: null,
        decisionSource: "INSPECTOR_L1_5",
        sellerEntitlementAmount: "452200000.00",
        buyerRefundAmount: "497800000.00",
        prevailingRole: "BUYER",
      },
    },
    {
      ...base,
      breachCaseId: BREACH_2,
      contractId: CONTRACT_ID,
      milestoneId: mid(2),
      breachReasonCode: "SHORTFALL",
      evidenceFileIds: [file(4)],
      status: "RESOLVED",
      decisionSource: "INSPECTOR_L1_5",
      reportedAt: at(-60 * 24 * 7),
      resolvedAt: at(-60 * 24 * 2),
      computedResolution: {
        inspectionReportId: "1e500000-0000-4000-8000-000000000014",
        issueType: "QUANTITY",
        acceptedQuantityKg: 9650,
        qualityDisposition: null,
        finalBreachingRole: null,
        decisionSource: "INSPECTOR_L1_5",
        sellerEntitlementAmount: "916750000.00",
        buyerRefundAmount: "33250000.00",
        prevailingRole: "BUYER",
      },
    },
  ]
}

export const milestoneIds = { mid, file }

export function contractTerms() {
  return {
    agreedPrice: price,
    currency: "VND",
    toleranceRate: 0.01,
    shortfallPenaltyThreshold: 0.05,
    buyerConfirmWindowDays: 3,
    contractTemplateCode: "VN_COFFEE_B2B",
    contractTemplateVersion: "1.0",
    totalContractValue: "7600000000.00",
  }
}
