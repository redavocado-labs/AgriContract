import { formatDecimalVnd } from "@/shared/lib/decimal-display"
import type {
  AdminAuditRecord,
  AuditSubjectType,
  BrokenReason,
  PartyAuditRecord,
} from "../api/audit"

const shortId = (id: string) =>
  id.length > 14 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id

function str(content: Record<string, unknown>, key: string): string | null {
  const value = content[key]
  if (typeof value === "string" && value.trim()) return value
  if (typeof value === "number" && Number.isFinite(value)) return String(value)
  return null
}

function kgText(content: Record<string, unknown>, key: string): string | null {
  const raw = content[key]
  if (typeof raw === "number" && Number.isFinite(raw)) {
    return `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 3 }).format(raw)} kg`
  }
  if (typeof raw === "string" && /^\d+(?:\.\d+)?$/.test(raw)) {
    return `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 3 }).format(Number(raw))} kg`
  }
  return null
}

function vndText(content: Record<string, unknown>, key: string): string | null {
  const raw = str(content, key)
  if (!raw) return null
  if (/^\d+(?:\.\d{1,2})?$/.test(raw)) {
    return `${formatDecimalVnd(raw.includes(".") ? raw : `${raw}.00`)} ₫`
  }
  return null
}

const roleText: Record<string, string> = {
  BUYER: "Bên mua",
  SELLER: "Bên bán",
  SYSTEM: "Hệ thống",
  INSPECTOR_L1_5: "Giám định nội bộ",
  INSPECTOR_L2: "Giám định độc lập",
  ARBITRATOR: "Trọng tài",
  MUTUAL: "Hai bên thỏa thuận",
}

const dispositionText: Record<string, string> = {
  CONFORMING: "Đạt chất lượng",
  PARTIALLY_CONFORMING: "Đạt một phần",
  NON_CONFORMING: "Không đạt chất lượng",
}

const terminationText: Record<string, string> = {
  WITHDRAW_OFFER: "rút đề nghị trước khi đủ chữ ký",
  ACTIVATION_FAILED: "không khóa được cọc kích hoạt",
  BREACH: "vi phạm hợp đồng",
  MUTUAL_TERMINATION: "hai bên thỏa thuận chấm dứt",
  MUTUAL_REPLACEMENT: "thay thế hợp đồng",
  FORCE_MAJEURE: "bất khả kháng",
}

const documentTypeText: Record<string, string> = {
  FINAL_CONTRACT: "bản hợp đồng cuối",
  SIGNED_CONTRACT: "bản hợp đồng đã ký",
  SETTLEMENT_ANNEX: "phụ lục tất toán",
}

export function isUnreadableAuditRecord(
  record: Pick<AdminAuditRecord, "sourceType" | "sourceEventType" | "content" | "createdAt">
): boolean {
  return (
    record.sourceType === null ||
    record.sourceEventType === null ||
    record.content === null ||
    record.createdAt === null
  )
}

export function formatAuditEventLabel(
  record: Pick<
    PartyAuditRecord | AdminAuditRecord,
    "sourceType" | "sourceEventType" | "content"
  >
): string {
  const { sourceType, sourceEventType, content } = record
  if (!sourceEventType || !content) return "Bản ghi không đọc được"

  switch (sourceEventType) {
    case "contract.signed":
      return "Hai bên ký hợp đồng"
    case "contract.document_generated": {
      const docType = str(content, "documentType")
      const label = docType ? (documentTypeText[docType] ?? docType) : null
      return label ? `Phát hành tài liệu PDF (${label})` : "Phát hành tài liệu PDF hợp đồng"
    }
    case "milestone.seller_weighed": {
      const weight = kgText(content, "sellerDeclaredWeight")
      const num = str(content, "declarationNumber")
      const parts = [weight, num ? `lần cân ${num}` : null].filter(Boolean)
      return parts.length ? `Bên bán cân ${parts.join(" · ")}` : "Bên bán khai báo số cân"
    }
    case "milestone.buyer_received": {
      const weight = kgText(content, "buyerReceivedWeight")
      return weight ? `Bên mua cân nhận ${weight}` : "Bên mua xác nhận đã nhận hàng"
    }
    case "milestone.buyer_confirmed": {
      const by = str(content, "confirmedBy")
      if (by === "SYSTEM_TIMEOUT") return "Tự xác nhận nhận đủ hàng khi hết hạn"
      return "Bên mua xác nhận nhận đủ hàng"
    }
    case "milestone.settled": {
      const amount = vndText(content, "actualAmount") ?? vndText(content, "settledAmount")
      return amount ? `Tất toán đợt giao — giải ngân ${amount}` : "Tất toán đợt giao"
    }
    case "milestone.force_majeure_claimed":
      return "Bên bán báo sự kiện bất khả kháng"
    case "milestone.force_majeure_resolved": {
      const decision = str(content, "decision")
      return decision === "APPROVED"
        ? "Chấp thuận yêu cầu bất khả kháng"
        : decision === "REJECTED"
          ? "Từ chối yêu cầu bất khả kháng"
          : "Xử lý yêu cầu bất khả kháng"
    }
    case "milestone.cancelled_with_penalty": {
      const penalty = vndText(content, "penaltyAmount") ?? vndText(content, "seizedAmount")
      const who = str(content, "finalBreachingRole")
      const whoLabel = who ? (roleText[who] ?? who) : null
      const details = [whoLabel ? `bên lỗi: ${whoLabel}` : null, penalty ? `phạt ${penalty}` : null].filter(Boolean)
      return details.length
        ? `Chấm dứt đợt giao có chế tài (${details.join(", ")})`
        : "Chấm dứt đợt giao có chế tài"
    }
    case "breach.reported": {
      const code = str(content, "breachReasonCode")
      const role = str(content, "allegedBreachingRole")
      const who = role ? (roleText[role] ?? role) : null
      const parts = [who ? `bên bị báo: ${who}` : null, code].filter(Boolean)
      return parts.length ? `Mở hồ sơ vi phạm (${parts.join(" · ")})` : "Mở hồ sơ vi phạm"
    }
    case "remedy.finalized": {
      const source = str(content, "decisionSource")
      const who = str(content, "finalBreachingRole")
      const parts = [
        source ? (roleText[source] ?? source) : null,
        who ? `bên lỗi: ${roleText[who] ?? who}` : null,
      ].filter(Boolean)
      return parts.length ? `Chế tài đã chốt (${parts.join(" · ")})` : "Chế tài đã chốt"
    }
    case "contract.terminated": {
      const kind = str(content, "terminationType")
      const kindLabel = kind ? (terminationText[kind] ?? kind) : null
      return kindLabel ? `Chấm dứt hợp đồng (${kindLabel})` : "Chấm dứt hợp đồng"
    }
    case "contract.settled":
      return "Hợp đồng hoàn tất"
    case "inspection.report_confirmed": {
      const result = str(content, "normalizedResult")
      const resultLabel = result ? (dispositionText[result] ?? result) : null
      const prefix =
        sourceType === "EXTERNAL_INSPECTION_REPORT" || str(content, "tier") === "LEVEL_2"
          ? "Kết quả giám định độc lập"
          : "Kết quả giám định nội bộ"
      return resultLabel ? `${prefix}: ${resultLabel}` : prefix
    }
    case "inspection.level2_commissioned": {
      const org = str(content, "org")
      return org ? `Uỷ quyền giám định độc lập (${org})` : "Uỷ quyền giám định độc lập"
    }
    case "bank.verifier_key_registered": {
      const fp = str(content, "publicKeyFingerprint")
      return fp
        ? `Đăng ký khoá bên xác minh ngoài (${shortId(fp)})`
        : "Đăng ký khoá bên xác minh ngoài"
    }
    case "bank.security_lock_changed": {
      const action = str(content, "action")
      const reason = str(content, "reason")
      const base =
        action === "UNLOCKED" || sourceType === "SECURITY_UNLOCK_TRIGGERED"
          ? "Mở khoá khẩn cấp toàn hệ thống"
          : "Kích hoạt khoá khẩn cấp toàn hệ thống"
      return reason ? `${base} (${reason})` : base
    }
    case "bank.suspicious_report_created":
      return "Ghi nhận cảnh báo giao dịch chia nhỏ"
    case "reputation.elevated_risk_cleared": {
      const reason = str(content, "reason")
      return reason ? `Gỡ cờ rủi ro cao (${reason})` : "Gỡ cờ rủi ro cao"
    }
    case "audit.viewed": {
      const admin = str(content, "adminUserId")
      const scope = str(content, "scope")
      const contractId = str(content, "contractId")
      const who = admin ? shortId(admin) : "không rõ"
      const target =
        scope === "CONTRACT" && contractId
          ? `hợp đồng ${shortId(contractId)}`
          : "toàn hệ thống"
      return `ADMIN ${who} xem nhật ký ${target}`
    }
    default:
      return sourceEventType
  }
}

export function brokenReasonMessage(reason: BrokenReason | null): string {
  switch (reason) {
    case "RECORD_HASH_MISMATCH":
      return "Nội dung bản ghi này đã bị sửa"
    case "SUBJECT_LINK_BROKEN":
      return "Mắt xích trước bản ghi này bị đứt (có bản ghi bị xoá hoặc chèn)"
    case "GLOBAL_LINK_BROKEN":
      return "Mắt xích toàn hệ thống bị đứt"
    default:
      return "Chuỗi bằng chứng không khớp"
  }
}

export function formatSubjectLabel(
  subjectType: AuditSubjectType | null,
  subjectId: string | null
): string {
  if (!subjectType || !subjectId) return "Không đọc được"
  switch (subjectType) {
    case "CONTRACT":
      return `Hợp đồng ${shortId(subjectId)}`
    case "USER_PAIR":
      return `Cặp người dùng ${shortId(subjectId)}`
    case "SYSTEM":
      return "Hệ thống"
  }
}

export function truncateAuditHash(hash: string): string {
  if (hash.length <= 16) return hash
  return `${hash.slice(0, 6)}…${hash.slice(-6)}`
}

export function newestFirst<T>(records: readonly T[]): T[] {
  return [...records].reverse()
}
