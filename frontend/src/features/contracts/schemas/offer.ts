import { z } from "zod"
import type { Fields } from "../types/contract"

const price = z
  .string()
  .regex(
    /^\d{1,8}\.\d{2}$/,
    "Nhập VND/kg với đúng 2 chữ số thập phân (vd 95000.00)."
  )
const percent = (min: number, max: number) =>
  z.coerce
    .number({ error: "Nhập một số." })
    .min(min, `Tối thiểu ${min}%.`)
    .max(max, `Tối đa ${max}%.`)
const days = (min: number, max: number) =>
  z.coerce
    .number({ error: "Nhập số ngày." })
    .int("Nhập số nguyên.")
    .min(min, `Tối thiểu ${min} ngày.`)
    .max(max, `Tối đa ${max} ngày.`)

export const offerSchema = z
  .object({
    agreedPrice: price,
    minimumUnitPrice: price,
    maximumUnitPrice: price,
    milestones: z
      .array(
        z.object({
          committedQuantity: z.coerce
            .number({ error: "Nhập khối lượng." })
            .positive("Khối lượng phải lớn hơn 0.")
            .max(1_000_000, "Tối đa 1.000.000 kg mỗi đợt."),
          expectedDeliveryDate: z
            .string()
            .regex(/^\d{4}-\d{2}-\d{2}$/, "Chọn ngày giao."),
          graceDays: days(0, 30),
        })
      )
      .min(1, "Cần ít nhất một đợt giao.")
      .max(100),
    coffeeType: z.enum(["ARABICA", "ROBUSTA"]),
    moisturePercent: percent(0, 100),
    foreignMatterPercent: percent(0, 100),
    blackBrokenBeansPercent: percent(0, 100),
    toleranceRate: percent(0, 10),
    shortfallPenaltyThreshold: percent(3, 15),
    buyerPenaltyRate: percent(0, 8),
    sellerPenaltyRate: percent(0, 8),
    buyerDepositRate: percent(3, 10),
    sellerDepositRate: percent(0, 100),
    buyerConfirmWindowDays: days(2, 10),
    fundingLeadBusinessDays: days(3, 60),
    inspectionRequirement: z.enum([
      "NONE_UNLESS_DISPUTED",
      "MANDATORY_BEFORE_SETTLEMENT",
    ]),
  })
  .refine(
    (v) =>
      Number(v.minimumUnitPrice) <= Number(v.agreedPrice) &&
      Number(v.agreedPrice) <= Number(v.maximumUnitPrice),
    { path: ["agreedPrice"], message: "Đơn giá phải nằm trong biên độ giá." }
  )
export type OfferValues = z.input<typeof offerSchema>
export type OfferOutput = z.output<typeof offerSchema>

const rate = (value: number) => Number((value / 100).toFixed(4))

/**
 * ContractTermsInput from the form. Signed defaults the form does not expose
 * (delivery allocation, deviation policy, legal profile) follow the reference
 * offer in backend/bruno/contract/06-create-contract.bru; both parties can still
 * review them on the detail page before signing.
 */
export function offerTerms(v: OfferOutput): Fields {
  const deviation = {
    direction: "MAXIMUM",
    tolerance: 0.5,
    penalty: 1,
    reject: 2,
    discountRate: 0.02,
  }
  return {
    agreedPrice: v.agreedPrice,
    currency: "VND",
    milestoneSchedule: v.milestones.map((m, index) => ({
      milestoneIndex: index + 1,
      committedQuantity: m.committedQuantity,
      expectedDeliveryDate: m.expectedDeliveryDate,
      graceDays: m.graceDays,
    })),
    qualityTerms: {
      commodity: "COFFEE",
      committedSpec: {
        commodity: "COFFEE",
        type: v.coffeeType,
        moisturePercent: v.moisturePercent,
        foreignMatterPercent: v.foreignMatterPercent,
        blackBrokenBeansPercent: v.blackBrokenBeansPercent,
      },
      deviationPolicy: {
        type: { rejectOnMismatch: true },
        moisturePercent: deviation,
        foreignMatterPercent: deviation,
        blackBrokenBeansPercent: deviation,
      },
    },
    deliveryTerms: {
      deliveryResponsibility: "SELLER_DELIVERS",
      riskTransferPoint: "BUYER_RECEIPT",
      titleTransferPoint: "FULL_PAYMENT",
      transportCostBearer: "SELLER",
      unloadingCostBearer: "BUYER",
    },
    inspectionTerms: {
      requirement: v.inspectionRequirement,
      requiredTier: "LEVEL_1_5",
      reportDueBusinessDays: 5,
      inspectionCostResponsibility: "LOSER_PAYS",
    },
    priceBand: {
      minimumUnitPrice: v.minimumUnitPrice,
      maximumUnitPrice: v.maximumUnitPrice,
    },
    priceAdjustmentRule: {
      acceptanceMode: "BOTH_ACCEPT",
      cutoff: "BEFORE_FUNDING_REQUEST",
      maximumAcceptedAdjustmentsPerMilestone: 1,
    },
    toleranceRate: rate(v.toleranceRate),
    shortfallPenaltyThreshold: rate(v.shortfallPenaltyThreshold),
    buyerPenaltyRate: rate(v.buyerPenaltyRate),
    sellerPenaltyRate: rate(v.sellerPenaltyRate),
    forceMajeureReportWindowDays: 7,
    buyerConfirmWindowDays: v.buyerConfirmWindowDays,
    fundingLeadBusinessDays: v.fundingLeadBusinessDays,
    buyerDepositRate: rate(v.buyerDepositRate),
    disputeFloorReleaseRate: 0.5,
    sellerDepositRate: rate(v.sellerDepositRate),
    level2InspectorOrg: null,
    legalProfile: {
      governingLaw: "VN_COMMERCIAL_LAW",
      contractType: "COFFEE_SALE",
      maxContractualPenaltyRate: 0.08,
      damagesPolicy: "COMMERCIAL_CUMULATIVE_IF_PROVEN",
    },
  }
}

/** Starting values: listing asking price, a ±10% band, declared quality. */
export function offerDefaults(listing: {
  askingUnitPrice: string
  declaredQualitySpec?: {
    type: "ARABICA" | "ROBUSTA"
    moisturePercent: number
    foreignMatterPercent: number
    blackBrokenBeansPercent: number
  } | null
  minOrderQuantity?: number | null
}): OfferValues {
  const asking = Number(listing.askingUnitPrice)
  const band = (factor: number) => Math.round(asking * factor).toFixed(2)
  const spec = listing.declaredQualitySpec
  return {
    agreedPrice: listing.askingUnitPrice,
    minimumUnitPrice: band(0.9),
    maximumUnitPrice: band(1.1),
    milestones: [
      {
        committedQuantity: listing.minOrderQuantity ?? "",
        expectedDeliveryDate: "",
        graceDays: 2,
      },
    ],
    coffeeType: spec?.type ?? "ROBUSTA",
    moisturePercent: spec?.moisturePercent ?? 12.5,
    foreignMatterPercent: spec?.foreignMatterPercent ?? 0.5,
    blackBrokenBeansPercent: spec?.blackBrokenBeansPercent ?? 5,
    toleranceRate: 5,
    shortfallPenaltyThreshold: 5,
    buyerPenaltyRate: 8,
    sellerPenaltyRate: 6,
    buyerDepositRate: 5,
    sellerDepositRate: 3,
    buyerConfirmWindowDays: 3,
    fundingLeadBusinessDays: 7,
    inspectionRequirement: "NONE_UNLESS_DISPUTED",
  }
}
