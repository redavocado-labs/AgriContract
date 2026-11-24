import { z } from "zod"

const party = z.enum(["BUYER", "SELLER"])
const money = z
  .string()
  .regex(/^\d+\.\d{2}$/)
  .max(19)
const timestamp = z.iso.datetime({ offset: true })

export const remedyDecisionSchema = z.object({
  remedyDecisionId: z.uuid(),
  status: z.enum(["PENDING_CONTEST", "FINALIZED"]),
  decisionSource: z.enum([
    "SYSTEM",
    "ARBITRATOR",
    "INSPECTOR_L1_5",
    "INSPECTOR_L2",
    "MUTUAL",
  ]),
  finalBreachingRole: party.nullable(),
  breachReasonCode: z.string().min(1).nullable(),
  qualityDisposition: z
    .enum(["CONFORMING", "PARTIALLY_CONFORMING", "NON_CONFORMING"])
    .nullable(),
  penaltyAmount: money,
  penaltyDebtAmount: money,
  inspectionFeeParty: z.enum(["BUYER", "SELLER", "SPLIT"]).nullable(),
  settledMilestoneCount: z.number().int().nonnegative(),
  createdAt: timestamp,
  finalizedAt: timestamp.nullable(),
  remedyLegs: z.array(
    z.object({
      remedyLegId: z.uuid(),
      remedyType: z.enum([
        "NONE",
        "PAYMENT_SETTLEMENT",
        "PAYMENT_REFUND",
        "DEPOSIT_FORFEITURE",
        "CONTRACTUAL_PENALTY",
        "DAMAGES_COMPENSATION",
      ]),
      fundType: z.enum([
        "BUYER_DEPOSIT",
        "SELLER_DEPOSIT",
        "MILESTONE_PAYMENT",
      ]),
      role: party,
      amount: money,
    })
  ),
})

export const terminalDecisionSchema = z.object({
  targetStatus: z.enum(["SETTLED", "TERMINATED"]),
  moneyCleared: z.boolean(),
  remedyDecision: remedyDecisionSchema,
})
export type RemedyDecision = z.infer<typeof remedyDecisionSchema>
export type TerminalDecision = z.infer<typeof terminalDecisionSchema>
