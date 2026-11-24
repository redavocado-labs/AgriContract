import { z } from "zod"
import { apiClient } from "@/shared/api/client"
import { milestoneSchema } from "@/features/contracts/api/contracts"
import { remedyDecisionSchema } from "@/features/contracts/api/remedy"
import type { Challenge } from "@/features/contracts/types/contract"
import type { IssueType } from "../lib/milestone-consent"

const money = z.string().regex(/^\d+\.00$/)
const instant = z.string().min(1)
const weight = z.number().positive().max(1_000_000)

// MilestoneDetailResponse and its views (golden-flow-api.yaml, D84).
const sellerDeclaration = z.object({
  declarationNumber: z.number().int().min(1),
  sellerDeclaredWeight: weight,
  sellerEvidenceFileId: z.string().min(1),
  deliveryCertificateFileId: z.string().nullable(),
  declaredAt: instant,
  correctionWindowClosesAt: instant,
})
const buyerReceipt = z.object({
  buyerReceivedWeight: weight,
  buyerEvidenceFileId: z.string().min(1),
  deliveryCertificateFileId: z.string().nullable(),
  receivedAt: instant,
  confirmWindowClosesAt: instant,
})
const flag = z.object({
  issueType: z.enum(["QUANTITY", "QUALITY"]),
  flaggedAt: instant,
  sellerResponseWindowClosesAt: instant,
  contestedAt: instant.nullable(),
  contestedBy: z.enum(["SELLER", "SYSTEM"]).nullable(),
})
const evidence = z.object({
  fileId: z.string().min(1),
  uploadedByRole: z.enum(["BUYER", "SELLER"]),
  status: z.enum(["PROCESSING", "READY", "FAILED"]),
  uploadedAt: instant,
})
const settlement = z.object({
  path: z.enum([
    "CLEAN_CONFIRMED",
    "CLEAN_AUTO_CONFIRMED",
    "SELLER_SILENT_QUANTITY",
    "INSPECTION_RESOLVED",
    "CONCEDED",
    "MUTUAL_SETTLEMENT",
  ]),
  billableQuantityKg: z.number().min(0).nullable(),
  sellerAmount: money,
  buyerRefundAmount: money,
  settledAt: instant,
})
const partyRole = z.enum(["BUYER", "SELLER"])
const positionAmount = z.string().regex(/^\d+\.\d{2}$/)
const disputeExit = z.object({
  buyerPositionAmount: positionAmount,
  sellerPositionAmount: positionAmount,
  buyerPositionRefundAmount: positionAmount.optional(),
  sellerPositionRefundAmount: positionAmount.optional(),
  concedeAllowed: z.boolean(),
  settlementAllowed: z.boolean(),
  openProposal: z.object({
    agreedSellerEntitlementAmount: positionAmount,
    agreedBuyerRefundAmount: positionAmount.optional(),
    proposedByRole: partyRole,
    proposedAt: instant,
  }).nullable(),
  freeCancelUntil: instant.nullish(),
  inspectionFeePartyLabel: z.string().nullish(),
})
export const milestoneDetailSchema = z.object({
  contractId: z.string().min(1),
  milestone: milestoneSchema,
  sellerDeclaration: sellerDeclaration.nullable(),
  buyerReceipt: buyerReceipt.nullable(),
  flag: flag.nullable(),
  evidence: z.array(evidence),
  settlement: settlement.nullable(),
  breachCaseId: z.string().nullable(),
  disputeExit: disputeExit.nullish(),
  remedyDecision: remedyDecisionSchema.nullish(),
})
export type MilestoneDetail = z.infer<typeof milestoneDetailSchema>
export type EvidenceView = z.infer<typeof evidence>
export type SettlementPath = z.infer<typeof settlement>["path"]

const computedResolution = z.object({
  inspectionReportId: z.string().min(1),
  issueType: z.enum(["QUANTITY", "QUALITY"]),
  acceptedQuantityKg: z.number().min(0),
  qualityDisposition: z
    .enum(["CONFORMING", "PARTIALLY_CONFORMING", "NON_CONFORMING"])
    .nullable(),
  finalBreachingRole: z.literal("SELLER").nullable(),
  decisionSource: z.literal("INSPECTOR_L1_5"),
  sellerEntitlementAmount: money,
  buyerRefundAmount: money,
  prevailingRole: z.enum(["BUYER", "SELLER"]),
})
export const breachCaseSchema = z.object({
  breachCaseId: z.string().min(1),
  contractId: z.string().min(1),
  milestoneId: z.string().nullable(),
  requestedBy: z.enum(["BUYER", "SELLER", "SYSTEM"]),
  allegedBreachingRole: z.enum(["BUYER", "SELLER"]).nullable(),
  breachReasonCode: z.string().min(1),
  severity: z.enum(["MINOR", "MATERIAL"]),
  evidenceFileIds: z.array(z.string()),
  finalBreachingRole: z.enum(["BUYER", "SELLER"]).nullable(),
  decisionSource: z
    .enum(["ARBITRATOR", "INSPECTOR_L1_5", "INSPECTOR_L2", "MUTUAL"])
    .nullable(),
  status: z.enum(["REPORTED", "UNDER_REVIEW", "RESOLVED"]),
  reportedAt: instant,
  resolvedAt: instant.nullable(),
  contestStatus: z.enum(["NOT_APPLICABLE", "OPEN", "CONTESTED", "EXPIRED"]),
  contestDeadline: instant.nullable(),
  computedResolution: computedResolution.nullable(),
})
export type BreachCase = z.infer<typeof breachCaseSchema>
export type ComputedResolution = z.infer<typeof computedResolution>

const base = (contractId: string, milestoneId: string) =>
  `/api/v1/contracts/${encodeURIComponent(contractId)}/milestones/${encodeURIComponent(milestoneId)}`

export async function getMilestoneDetail(
  contractId: string,
  milestoneId: string,
  signal?: AbortSignal
) {
  const { data } = await apiClient.get(base(contractId, milestoneId), {
    signal,
  })
  const detail = milestoneDetailSchema.parse(data)
  if (
    detail.contractId !== contractId ||
    detail.milestone.milestoneId !== milestoneId
  )
    throw new Error("Phản hồi không khớp đợt giao yêu cầu.")
  return detail
}

/** Raw-body upload (D71). The browser sets Content-Length for a File body. */
export async function uploadEvidence(
  contractId: string,
  milestoneId: string,
  file: File,
  key: string
) {
  const { data } = await apiClient.post(
    `${base(contractId, milestoneId)}/evidence`,
    file,
    {
      headers: { "Content-Type": file.type, "Idempotency-Key": key },
      timeout: 60_000,
    }
  )
  return z
    .object({
      fileId: z.string().min(1),
      status: z.enum(["PROCESSING", "READY", "FAILED"]),
    })
    .parse(data)
}

export async function readEvidence(
  contractId: string,
  milestoneId: string,
  fileId: string,
  signal?: AbortSignal
) {
  const { data } = await apiClient.get(
    `${base(contractId, milestoneId)}/evidence/${encodeURIComponent(fileId)}`,
    { responseType: "blob", signal }
  )
  if (!(data instanceof Blob)) throw new Error("Không đọc được ảnh.")
  return data
}

export interface ConsentInput {
  statementVersion: string
  presentedContentHash: string
}

async function command(url: string, body: unknown, key: string) {
  const response = await apiClient.post(url, body, {
    headers: { "Idempotency-Key": key },
  })
  if (response.status !== 204)
    throw new Error("Chưa nhận được xác nhận từ máy chủ. Vui lòng tải lại.")
}

export function recordSellerWeighed(
  contractId: string,
  milestoneId: string,
  body: {
    sellerDeclaredWeight: number
    sellerEvidenceFileId: string
    deliveryCertificateFileId?: string
    consent: ConsentInput
  },
  key: string
) {
  return command(`${base(contractId, milestoneId)}/weigh`, body, key)
}

export function recordBuyerReceipt(
  contractId: string,
  milestoneId: string,
  body: {
    buyerReceivedWeight: number
    buyerEvidenceFileId: string
    deliveryCertificateFileId?: string
    consent: ConsentInput
  },
  key: string
) {
  return command(`${base(contractId, milestoneId)}/receive`, body, key)
}

export function confirmMilestoneClean(
  contractId: string,
  milestoneId: string,
  consent: ConsentInput,
  key: string
) {
  return command(`${base(contractId, milestoneId)}/confirm`, consent, key)
}

export function flagMilestoneIssue(
  contractId: string,
  milestoneId: string,
  issueType: IssueType,
  consent: ConsentInput,
  key: string
) {
  return command(
    `${base(contractId, milestoneId)}/flag`,
    { issueType, consent },
    key
  )
}

export function respondToMilestoneFlag(
  contractId: string,
  milestoneId: string,
  consent: ConsentInput,
  key: string
) {
  return command(`${base(contractId, milestoneId)}/respond`, consent, key)
}

const breachPath = (contractId: string, breachCaseId: string) =>
  `/api/v1/contracts/${encodeURIComponent(contractId)}/breach-cases/${encodeURIComponent(breachCaseId)}`

export async function getBreachCase(
  contractId: string,
  breachCaseId: string,
  signal?: AbortSignal
) {
  const { data } = await apiClient.get(breachPath(contractId, breachCaseId), {
    signal,
  })
  const detail = breachCaseSchema.parse(data)
  if (detail.contractId !== contractId || detail.breachCaseId !== breachCaseId)
    throw new Error("Phản hồi không khớp hồ sơ tranh chấp yêu cầu.")
  return detail
}

export async function listArbitrationQueue(page: number, signal?: AbortSignal) {
  const { data } = await apiClient.get("/api/v1/arbitration/breach-cases", {
    params: { awaitingApproval: true, page, size: 20 },
    signal,
  })
  return z
    .object({
      content: z.array(breachCaseSchema),
      page: z.number().int().nonnegative(),
      size: z.number().int().positive(),
      totalElements: z.number().int().nonnegative(),
      totalPages: z.number().int().nonnegative(),
    })
    .parse(data)
}

export function reviewBreachCase(
  contractId: string,
  breachCaseId: string,
  key: string
) {
  return command(
    `${breachPath(contractId, breachCaseId)}/review`,
    undefined,
    key
  )
}

/** D81: the request must equal computedResolution; the Arbitrator only approves. */
export function approveComputedResolution(
  breachCase: BreachCase,
  resolution: ComputedResolution,
  key: string
) {
  return command(
    `${breachPath(breachCase.contractId, breachCase.breachCaseId)}/resolve`,
    {
      finalBreachingRole: resolution.finalBreachingRole,
      decisionSource: resolution.decisionSource,
      evidenceRefs: [resolution.inspectionReportId],
    },
    key
  )
}

export function contestBreachCase(
  breach: BreachCase,
  reason: string,
  evidenceFileIds: string[],
  key: string
) {
  return command(
    `${breachPath(breach.contractId, breach.breachCaseId)}/contest`,
    { reason, evidenceFileIds },
    key
  )
}

export function addContestEvidence(breach: BreachCase, fileIds: string[], key: string) {
  return command(
    `${breachPath(breach.contractId, breach.breachCaseId)}/contest/evidence`,
    { fileIds },
    key
  )
}

export type DisputeExitIntent =
  | { kind: "concede" }
  | { kind: "settlement"; amount: string }

const disputePath = (detail: MilestoneDetail) =>
  `${base(detail.contractId, detail.milestone.milestoneId)}/dispute`

export async function initiateDisputeExit(
  detail: MilestoneDetail,
  intent: DisputeExitIntent,
  consent: ConsentInput,
  key: string
): Promise<Challenge> {
  const { data } = await apiClient.post(
    `${disputePath(detail)}/${intent.kind}/initiate`,
    {
      consentStatementVersion: consent.statementVersion,
      presentedContentHash: consent.presentedContentHash,
      ...(intent.kind === "settlement" && { agreedSellerEntitlementAmount: intent.amount }),
    },
    { headers: { "Idempotency-Key": key } }
  )
  return z.object({
    otpId: z.uuid(),
    expiresAt: z.iso.datetime({ offset: true }),
  }).parse(data)
}

export function verifyDisputeExit(
  detail: MilestoneDetail,
  intent: DisputeExitIntent,
  challenge: Challenge,
  otpCode: string,
  key: string
) {
  if (Date.parse(challenge.expiresAt) <= Date.now())
    throw new Error("Mã OTP đã hết hạn. Vui lòng gửi mã mới.")
  if (!/^\d{6}$/.test(otpCode)) throw new Error("Nhập đủ 6 chữ số OTP.")
  return command(
    `${disputePath(detail)}/${intent.kind}/verify`,
    { otpId: challenge.otpId, otpCode },
    key
  )
}

export async function endSettlementProposal(detail: MilestoneDetail, key: string) {
  const { status } = await apiClient.delete(`${disputePath(detail)}/settlement`, {
    headers: { "Idempotency-Key": key },
  })
  if (status !== 204) throw new Error("Chưa nhận được xác nhận từ máy chủ.")
}

export function retryRemedyLegs(contractId: string, key: string) {
  return command(
    `/api/v1/contracts/${encodeURIComponent(contractId)}/remedy/retry-legs`,
    undefined,
    key
  )
}
