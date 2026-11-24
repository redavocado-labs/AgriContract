import type { TimelineNode } from "@/shared/ui/milestone-timeline"
import type {
  Milestone,
  MilestoneStatus,
} from "@/features/contracts/types/contract"
import type { MilestoneDetail, SettlementPath } from "../api/milestones"
import { displayKg } from "./milestone-consent"
import { vietnamTime } from "@/shared/lib/vietnam-time"

type BadgeVariant =
  "primary" | "success" | "warning" | "danger" | "info" | "neutral"

export const milestoneStatusLabel: Record<MilestoneStatus, string> = {
  CREATED: "Chờ khoá tiền đợt",
  IN_PROGRESS: "Đang chuẩn bị hàng",
  SELLER_WEIGHED: "Đã cân, đang vận chuyển",
  BUYER_RECEIVED: "Đã nhận, chờ xác nhận",
  AWAITING_SELLER_RESPONSE: "Chờ bên bán phản hồi",
  CONTESTED: "Đang giám định",
  FORCE_MAJEURE_PENDING_REVIEW: "Chờ xét bất khả kháng",
  SETTLED: "Đã tất toán",
}

export const milestoneStatusVariant: Record<MilestoneStatus, BadgeVariant> = {
  CREATED: "neutral",
  IN_PROGRESS: "info",
  SELLER_WEIGHED: "info",
  BUYER_RECEIVED: "warning",
  AWAITING_SELLER_RESPONSE: "warning",
  CONTESTED: "danger",
  FORCE_MAJEURE_PENDING_REVIEW: "warning",
  SETTLED: "success",
}

export const fundingLabel: Record<Milestone["fundingStatus"], string> = {
  NOT_REQUESTED: "Chưa tới ngày khoá tiền",
  FUNDING_PENDING: "Đang khoá tiền đợt",
  LOCKED: "Đã khoá tiền đợt",
  FUNDING_FAILED: "Khoá tiền đợt thất bại",
}

export const settlementPathLabel: Record<SettlementPath, string> = {
  CLEAN_CONFIRMED: "Bên mua xác nhận đúng",
  CLEAN_AUTO_CONFIRMED: "Tự xác nhận khi hết hạn",
  SELLER_SILENT_QUANTITY: "Bên bán không phản hồi báo thiếu cân",
  INSPECTION_RESOLVED: "Theo kết quả giám định",
  CONCEDED: "Bên bán chấp nhận số bên mua",
  MUTUAL_SETTLEMENT: "Hai bên thoả thuận",
}

export const issueTypeLabel = {
  QUANTITY: "Thiếu khối lượng",
  QUALITY: "Sai chất lượng",
} as const

const kg = (value: number) => `${displayKg(value)} kg`
const shortTime = (value?: string | null) =>
  value ? vietnamTime(value).replace(" (giờ Việt Nam)", "") : ""

/**
 * Timeline nodes of the existing MilestoneTimeline. The dispute branch
 * (AWAITING_SELLER_RESPONSE -> CONTESTED) is shown only once a flag exists.
 */
export function milestoneTimeline(detail: MilestoneDetail): TimelineNode[] {
  const {
    milestone: m,
    sellerDeclaration,
    buyerReceipt,
    flag,
    settlement,
  } = detail
  const order: MilestoneStatus[] = flag
    ? [
        "CREATED",
        "IN_PROGRESS",
        "SELLER_WEIGHED",
        "BUYER_RECEIVED",
        "AWAITING_SELLER_RESPONSE",
        "CONTESTED",
        "SETTLED",
      ]
    : ["CREATED", "IN_PROGRESS", "SELLER_WEIGHED", "BUYER_RECEIVED", "SETTLED"]
  const current =
    m.status === "FORCE_MAJEURE_PENDING_REVIEW"
      ? order.indexOf(sellerDeclaration ? "SELLER_WEIGHED" : "IN_PROGRESS")
      : order.indexOf(m.status)
  const meta: Partial<Record<MilestoneStatus, string>> = {
    CREATED: `Khoá tiền dự kiến ${vietnamTime(m.fundingScheduledDate)}`,
    IN_PROGRESS: `Hạn giao ${vietnamTime(m.effectiveDeliveryDeadline)}`,
    SELLER_WEIGHED: sellerDeclaration
      ? `${kg(sellerDeclaration.sellerDeclaredWeight)} · ${shortTime(sellerDeclaration.declaredAt)}`
      : "Bên bán cân trước khi xe chạy",
    BUYER_RECEIVED: buyerReceipt
      ? `${kg(buyerReceipt.buyerReceivedWeight)} · ${shortTime(buyerReceipt.receivedAt)}`
      : "Bên mua cân lại khi nhận",
    AWAITING_SELLER_RESPONSE: flag
      ? `${issueTypeLabel[flag.issueType]} · ${shortTime(flag.flaggedAt)}`
      : "",
    CONTESTED: flag?.contestedAt
      ? `Giám định Level 1.5 · ${shortTime(flag.contestedAt)}`
      : "Giám định Level 1.5 nếu bên bán phản đối",
    SETTLED: settlement
      ? `${settlementPathLabel[settlement.path]} · ${shortTime(settlement.settledAt)}`
      : "Giải ngân và hoàn tiền theo kết quả",
  }
  return order.map((status, index) => ({
    key: status,
    title: milestoneStatusLabel[status],
    meta: meta[status] ?? "",
    status:
      m.status === "SETTLED" || index < current
        ? "done"
        : index === current
          ? "active"
          : "upcoming",
  }))
}

/** Whole days left before a server deadline (rounded down); display only. */
export function daysLeft(deadline: string, now = Date.now()) {
  return Math.floor((Date.parse(deadline) - now) / 86_400_000)
}
export function secondsLeft(deadline: string, now = Date.now()) {
  return Math.max(0, Math.floor((Date.parse(deadline) - now) / 1000))
}

export const breachStatusLabel = {
  REPORTED: "Mới nhận",
  UNDER_REVIEW: "Đang xem xét",
  RESOLVED: "Đã duyệt",
} as const
