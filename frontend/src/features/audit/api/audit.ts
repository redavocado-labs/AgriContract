import { z } from "zod"
import { apiClient } from "@/shared/api/client"
import { parseApiError } from "@/shared/api/errors"

const hexHash = z.string().regex(/^[a-f0-9]{64}$/)

export const subjectTypeSchema = z.enum(["CONTRACT", "USER_PAIR", "SYSTEM"])
export type AuditSubjectType = z.infer<typeof subjectTypeSchema>

export const anchorStatusSchema = z.enum(["NOT_ANCHORED", "ANCHORED"])
export type AnchorStatus = z.infer<typeof anchorStatusSchema>

export const partyAuditRecordSchema = z.object({
  recordId: z.string().min(1),
  sourceType: z.string(),
  sourceEventType: z.string(),
  content: z.record(z.string(), z.unknown()),
  recordHash: hexHash,
  createdAt: z.string().min(1),
})
export type PartyAuditRecord = z.infer<typeof partyAuditRecordSchema>

export const contractAuditTrailSchema = z.object({
  contractId: z.string().min(1),
  currentRecordHash: hexHash,
  anchorStatus: anchorStatusSchema,
  records: z.array(partyAuditRecordSchema),
})
export type ContractAuditTrail = z.infer<typeof contractAuditTrailSchema>

// Admin records tolerate null payload fields when a stored row fails deserialization.
export const adminAuditRecordSchema = z.object({
  recordId: z.string().min(1),
  globalSeq: z.number().int().min(1),
  subjectType: subjectTypeSchema.nullable(),
  subjectId: z.string().nullable(),
  sourceType: z.string().nullable(),
  sourceEventType: z.string().nullable(),
  sourceEventId: z.string().nullable(),
  content: z.record(z.string(), z.unknown()).nullable(),
  recordHash: hexHash,
  prevHashSubject: hexHash.nullable(),
  prevHashGlobal: hexHash.nullable(),
  createdAt: z.string().nullable(),
})
export type AdminAuditRecord = z.infer<typeof adminAuditRecordSchema>

export const adminContractAuditTrailSchema = z.object({
  contractId: z.string().min(1),
  currentRecordHash: hexHash,
  anchorStatus: anchorStatusSchema,
  records: z.array(adminAuditRecordSchema),
})
export type AdminContractAuditTrail = z.infer<typeof adminContractAuditTrailSchema>

export const adminAuditRecordPageSchema = z.object({
  content: z.array(adminAuditRecordSchema),
  page: z.number().int().min(0),
  size: z.number().int().min(1),
  totalElements: z.number().int().min(0),
  totalPages: z.number().int().min(0),
})
export type AdminAuditRecordPage = z.infer<typeof adminAuditRecordPageSchema>

export const brokenReasonSchema = z.enum([
  "RECORD_HASH_MISMATCH",
  "SUBJECT_LINK_BROKEN",
  "GLOBAL_LINK_BROKEN",
])
export type BrokenReason = z.infer<typeof brokenReasonSchema>

export const auditChainVerificationSchema = z.object({
  scope: z.enum(["CONTRACT", "GLOBAL"]),
  contractId: z.string().nullable(),
  status: z.enum(["INTACT", "BROKEN"]),
  checkedRecords: z.number().int().min(0),
  brokenRecordId: z.string().nullable(),
  brokenReason: brokenReasonSchema.nullable(),
  checkedAt: z.string().min(1),
  anchorStatus: anchorStatusSchema,
})
export type AuditChainVerification = z.infer<typeof auditChainVerificationSchema>

export async function getPartyContractAuditTrail(
  contractId: string,
  signal?: AbortSignal
): Promise<ContractAuditTrail> {
  const { data } = await apiClient.get(
    `/api/v1/audit/contracts/${encodeURIComponent(contractId)}/records`,
    { signal }
  )
  return contractAuditTrailSchema.parse(data)
}

export async function verifyPartyContractAuditTrail(
  contractId: string,
  signal?: AbortSignal
): Promise<AuditChainVerification> {
  const { data } = await apiClient.get(
    `/api/v1/audit/contracts/${encodeURIComponent(contractId)}/verification`,
    { signal }
  )
  return auditChainVerificationSchema.parse(data)
}

export async function getAdminContractAuditTrail(
  contractId: string,
  signal?: AbortSignal
): Promise<AdminContractAuditTrail> {
  const { data } = await apiClient.get(
    `/api/v1/admin/audit/contracts/${encodeURIComponent(contractId)}/records`,
    { signal }
  )
  return adminContractAuditTrailSchema.parse(data)
}

export async function verifyAdminContractAuditTrail(
  contractId: string,
  signal?: AbortSignal
): Promise<AuditChainVerification> {
  const { data } = await apiClient.get(
    `/api/v1/admin/audit/contracts/${encodeURIComponent(contractId)}/verification`,
    { signal }
  )
  return auditChainVerificationSchema.parse(data)
}

export async function listAdminAuditRecords(
  params: {
    subjectType?: AuditSubjectType
    page?: number
    size?: number
  },
  signal?: AbortSignal
): Promise<AdminAuditRecordPage> {
  const { data } = await apiClient.get("/api/v1/admin/audit/records", {
    params: {
      ...(params.subjectType ? { subjectType: params.subjectType } : {}),
      page: params.page ?? 0,
      size: params.size ?? 20,
    },
    signal,
  })
  return adminAuditRecordPageSchema.parse(data)
}

export async function verifyGlobalAuditChain(
  signal?: AbortSignal
): Promise<AuditChainVerification> {
  const { data } = await apiClient.get("/api/v1/admin/audit/verification", {
    signal,
  })
  return auditChainVerificationSchema.parse(data)
}

export function auditError(error: unknown): string {
  const parsed = parseApiError(error)
  if (parsed.code === "AUDIT_TRAIL_NOT_FOUND")
    return "Hợp đồng này chưa có bản ghi bằng chứng."
  if (parsed.status === 400)
    return "Mã hợp đồng hoặc tham số tra cứu không hợp lệ."
  if (parsed.status === 403)
    return "Bạn không có quyền xem nhật ký bằng chứng này."
  if (parsed.status === 401)
    return "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
  return parsed.message || "Không tải được nhật ký bằng chứng từ máy chủ."
}
