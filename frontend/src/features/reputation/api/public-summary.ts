import { z } from "zod"
import { apiClient } from "@/shared/api/client"
import { APP_ROLES } from "@/shared/auth/auth-store"

const lockHistoryEntry = z.object({
  lockedAt: z.iso.datetime({ offset: true }),
  lockedUntil: z.iso.datetime({ offset: true }),
  lockDurationDays: z.number().int().positive(),
  penalizedRole: z.enum(["BUYER", "SELLER"]),
  status: z.enum(["ACTIVE", "EXPIRED", "UNLOCKED_EARLY"]),
})
const summarySchema = z.object({
  userId: z.guid(),
  score: z.number().min(0).max(100).nullable(),
  locked: z.boolean(),
  lockHistory: z.array(lockHistoryEntry),
})

export type ReputationSummary = z.infer<typeof summarySchema>

const partnerSchema = z.object({
  userId: z.guid(),
  organizationName: z.string().min(1),
  role: z.enum(APP_ROLES),
  verificationStatus: z.enum(["PENDING", "VERIFIED", "REJECTED"]),
})

export async function getPublicPartner(userId: string, signal?: AbortSignal) {
  const { data } = await apiClient.get<unknown>(`/api/v1/users/${encodeURIComponent(userId)}`, { signal })
  const partner = partnerSchema.parse(data)
  if (partner.userId.toLowerCase() !== userId.toLowerCase())
    throw new Error("Hồ sơ đối tác trả về không khớp.")
  return partner
}

export async function getPublicReputationSummary(userId: string, signal?: AbortSignal) {
  const { data } = await apiClient.get<unknown>(
    `/api/v1/reputation/${encodeURIComponent(userId)}/public-summary`,
    { signal }
  )
  const summary = summarySchema.parse(data)
  if (summary.userId.toLowerCase() !== userId.toLowerCase())
    throw new Error("Hồ sơ uy tín trả về không khớp.")
  return summary
}
