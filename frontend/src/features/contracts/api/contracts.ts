import { z } from "zod"
import { apiClient } from "@/shared/api/client"
import type {
  Contract,
  ContractPage,
  Fields,
  Milestone,
  ParticipantRole,
} from "../types/contract"

const fields = z.record(z.string(), z.unknown())
const money2 = z.string().regex(/^\d+\.\d{2}$/)
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/)
// MilestoneResponse (golden-flow-api.yaml), additionalProperties: false.
export const milestoneSchema = z.object({
  milestoneId: z.string().min(1),
  milestoneIndex: z.number().int().min(1),
  status: z.enum([
    "CREATED",
    "IN_PROGRESS",
    "SELLER_WEIGHED",
    "BUYER_RECEIVED",
    "AWAITING_SELLER_RESPONSE",
    "CONTESTED",
    "FORCE_MAJEURE_PENDING_REVIEW",
    "SETTLED",
  ]),
  fundingStatus: z.enum([
    "NOT_REQUESTED",
    "FUNDING_PENDING",
    "LOCKED",
    "FUNDING_FAILED",
  ]),
  fundingScheduledDate: date,
  committedQuantity: z.number().positive(),
  batchAmount: money2.nullable(),
  effectiveUnitPrice: money2,
  acceptedPriceAdjustment: fields.nullable(),
  expectedDeliveryDate: date,
  graceDays: z.number().int().nonnegative(),
  fundingDelayBusinessDays: z.number().int().nonnegative(),
  effectiveDeliveryDeadline: date,
})
const activationRecovery = z.object({
  depositLockFailedAt: z.string(),
  oneSidedCancelAllowedFrom: z.string(),
  cancelRequestedBy: z.array(z.enum(["BUYER", "SELLER"])),
})
/**
 * ContractResponse for a coffee contract; a Phase 1 row answers its old body
 * (organisation names, no signedContentHash) and stays read only. Both are raw
 * DTOs without the old success/data wrapper (D32).
 */
const wire = z.object({
  contractId: z.string().min(1),
  listingId: z.string().min(1),
  buyerId: z.string().min(1),
  sellerId: z.string().min(1),
  status: z.string().min(1),
  terms: fields.nullable(),
  buyerOrgName: z.string().nullish(),
  sellerOrgName: z.string().nullish(),
  productName: z.string().nullish(),
  signedContentHash: z
    .string()
    .regex(/^[a-f0-9]{64}$/)
    .nullish(),
  reservedQuantity: z.union([z.number().finite(), z.string()]).nullish(),
  milestones: z.array(milestoneSchema).optional(),
  activationRecovery: activationRecovery.nullish(),
})

export function mapContract(value: unknown, userId: string): Contract {
  const data = wire.parse(value)
  if (data.buyerId !== userId && data.sellerId !== userId)
    throw new Error("Bạn không phải là một bên của hợp đồng này.")
  const coffee =
    typeof value === "object" &&
    value !== null &&
    Object.prototype.hasOwnProperty.call(value, "signedContentHash")
  return {
    id: data.contractId,
    listingId: data.listingId,
    buyerId: data.buyerId,
    sellerId: data.sellerId,
    buyerName: data.buyerOrgName || undefined,
    sellerName: data.sellerOrgName || undefined,
    productName: data.productName || undefined,
    status: data.status,
    generation: coffee ? "coffee" : "legacy",
    terms: data.terms ?? {},
    hash: data.signedContentHash ?? null,
    reservedQuantity:
      data.reservedQuantity == null ? undefined : String(data.reservedQuantity),
    milestones: (data.milestones ?? []) as Milestone[],
    activationRecovery: data.activationRecovery ?? undefined,
  }
}
export function mapContractPage(
  value: unknown,
  userId: string,
  role: ParticipantRole
): ContractPage {
  const data = z
    .object({
      content: z.array(z.unknown()),
      page: z.number().int().nonnegative(),
      size: z.number().int().positive(),
      totalPages: z.number().int().nonnegative(),
      totalElements: z.number().int().nonnegative(),
    })
    .parse(value)
  const content = data.content.map((item) => mapContract(item, userId))
  if (
    content.some(
      (item) => (role === "BUYER" ? item.buyerId : item.sellerId) !== userId
    )
  )
    throw new Error("Danh sách trả về không khớp vai trò đang chọn.")
  return {
    content,
    page: data.page,
    size: data.size,
    totalPages: data.totalPages,
    totalElements: data.totalElements,
  }
}
const path = (id: string) => `/api/v1/contracts/${encodeURIComponent(id)}`
export async function listContracts(
  userId: string,
  role: ParticipantRole,
  status: string,
  page: number,
  signal?: AbortSignal
) {
  const response = await apiClient.get("/api/v1/contracts", {
    params: { role, status: status || undefined, page, size: 20 },
    signal,
  })
  return mapContractPage(response.data, userId, role)
}
export async function getContract(
  id: string,
  userId: string,
  signal?: AbortSignal
) {
  const response = await apiClient.get(path(id), { signal })
  const contract = mapContract(response.data, userId)
  if (contract.id !== id)
    throw new Error("Phản hồi không khớp mã hợp đồng yêu cầu.")
  return contract
}

// Exact allowlist from ContractTermsInput. Never send derived/template fields.
export const inputKeys = [
  "agreedPrice",
  "currency",
  "milestoneSchedule",
  "qualityTerms",
  "deliveryTerms",
  "inspectionTerms",
  "priceBand",
  "priceAdjustmentRule",
  "toleranceRate",
  "shortfallPenaltyThreshold",
  "buyerPenaltyRate",
  "sellerPenaltyRate",
  "forceMajeureReportWindowDays",
  "buyerConfirmWindowDays",
  "fundingLeadBusinessDays",
  "buyerDepositRate",
  "disputeFloorReleaseRate",
  "sellerDepositRate",
  "level2InspectorOrg",
  "legalProfile",
] as const
export function termsInput(snapshot: Fields, agreedPrice: string): Fields {
  const input: Fields = {}
  for (const key of inputKeys) {
    // fundingLeadBusinessDays defaults to 7 when omitted; level2InspectorOrg is nullable.
    if (key === "fundingLeadBusinessDays" && snapshot[key] == null) continue
    if (!(key in snapshot) && key !== "level2InspectorOrg")
      throw new Error("Điều khoản chưa đầy đủ; vui lòng tải lại.")
    input[key] = snapshot[key] ?? null
  }
  input.agreedPrice = agreedPrice
  return input
}
/** PATCH /terms before the first signature (#93); 409 CONTRACT_TERMS_FROZEN after it. */
export async function patchTerms(
  id: string,
  input: Fields,
  key: string,
  userId: string
) {
  if (
    Object.keys(input).some(
      (field) => !inputKeys.includes(field as (typeof inputKeys)[number])
    )
  )
    throw new Error("Không được gửi trường do máy chủ quản lý.")
  const response = await apiClient.patch(`${path(id)}/terms`, input, {
    headers: { "Idempotency-Key": key },
  })
  const contract = mapContract(response.data, userId)
  if (contract.id !== id) throw new Error("Phản hồi không khớp hợp đồng.")
  return contract
}

/** WITHDRAW_OFFER (#233): either party, before the second signature. 204, no body. */
export async function withdrawOffer(id: string, key: string) {
  const response = await apiClient.post(`${path(id)}/withdraw`, undefined, {
    headers: { "Idempotency-Key": key },
  })
  if (response.status !== 204)
    throw new Error("Chưa nhận được xác nhận rút đề nghị. Vui lòng tải lại.")
}

/** createContract (#232): BUYER offers on a listing; 201 with ContractResponse. */
export async function createContract(
  request: { listingId: string; listingVersionToken: string; terms: Fields },
  key: string,
  userId: string
) {
  if (
    Object.keys(request.terms).some(
      (field) => !inputKeys.includes(field as (typeof inputKeys)[number])
    )
  )
    throw new Error("Không được gửi trường do máy chủ quản lý.")
  const response = await apiClient.post("/api/v1/contracts", request, {
    headers: { "Idempotency-Key": key },
  })
  return mapContract(response.data, userId)
}
