/**
 * Milestone consent (milestone-escrow design §3.2.3, D84): fixed statements
 * shown word for word with their placeholders filled, and the
 * presentedContentHash recipe the server recomputes. Keep this the only place
 * that knows either.
 */
export type MilestoneConsentAction =
  | "DECLARE_SELLER_WEIGHT"
  | "RECORD_BUYER_RECEIPT"
  | "CONFIRM_CLEAN_RECEIPT"
  | "FLAG_MILESTONE_ISSUE"
  | "CONTEST_MILESTONE_FLAG"

export type IssueType = "QUANTITY" | "QUALITY"

export function milestoneStatementVersion(action: MilestoneConsentAction) {
  return action === "FLAG_MILESTONE_ISSUE" ? "1.1" : "1.0"
}

/** Placeholder rendering for the statement: Vietnamese grouping, up to 3 decimals. */
export function displayKg(value: number | string) {
  const number = typeof value === "string" ? Number(value) : value
  return new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 3 }).format(
    number
  )
}

export function milestoneStatement(
  action: MilestoneConsentAction,
  values: {
    x?: number | string
    s?: number | string
    b?: number | string
    issueType?: IssueType
  } = {}
) {
  switch (action) {
    case "DECLARE_SELLER_WEIGHT":
      return `Tôi xác nhận lô hàng của đợt giao này đã được cân với khối lượng ${displayKg(values.x ?? 0)} kg như tôi nhập, ảnh đính kèm là ảnh chụp thật của lần cân đó, và tôi chịu trách nhiệm về số liệu đã khai.`
    case "RECORD_BUYER_RECEIPT":
      return `Tôi xác nhận đã nhận lô hàng của đợt giao này và cân được ${displayKg(values.x ?? 0)} kg như tôi nhập, ảnh đính kèm là ảnh chụp thật của lần cân đó. Tôi hiểu số này không sửa được sau khi gửi.`
    case "CONFIRM_CLEAN_RECEIPT":
      return `Tôi xác nhận lô hàng của đợt giao này đạt yêu cầu, với số cân bên bán khai ${displayKg(values.s ?? 0)} kg và bên mua cân ${displayKg(values.b ?? 0)} kg, và đồng ý tất toán đợt theo khối lượng tính tiền hệ thống tính. Việc xác nhận nhận hàng không làm mất quyền khiếu nại hay giám định còn hiệu lực theo hợp đồng.`
    case "FLAG_MILESTONE_ISSUE":
      return `Tôi báo lô hàng của đợt giao này có vấn đề về ${values.issueType === "QUALITY" ? "chất lượng" : "khối lượng"}. Tôi hiểu đây mới là báo lỗi: tiền của đợt vẫn được giữ cho tới khi bên bán phản hồi hoặc có kết quả giám định. Tôi cam kết giữ riêng lô hàng này, không sử dụng hay trộn với hàng khác, cho tới khi giám định xong.`
    case "CONTEST_MILESTONE_FLAG":
      return "Tôi không đồng ý với báo lỗi của bên mua và yêu cầu giám định độc lập. Tôi hiểu kết quả giám định là căn cứ tất toán đợt này, và bên không được kết quả giám định ủng hộ chịu phí giám định."
  }
}

/**
 * kg with exactly 3 decimals ("9700.000"). A string from an input is kept
 * digit for digit, so no float rounding sneaks into the hash.
 */
export function kg3(value: number | string) {
  if (typeof value === "string") {
    const match = /^(\d+)(?:\.(\d{0,3}))?$/.exec(value.trim())
    if (!match) throw new Error("Khối lượng không hợp lệ.")
    return `${String(Number(match[1]))}.${(match[2] ?? "").padEnd(3, "0")}`
  }
  if (!Number.isFinite(value) || value < 0)
    throw new Error("Khối lượng không hợp lệ.")
  return value.toFixed(3)
}

const uuid = (value: string | null | undefined) => (value ?? "").toLowerCase()

type Parts =
  | {
      action: "DECLARE_SELLER_WEIGHT"
      milestoneId: string
      sellerDeclaredWeight: number | string
      sellerEvidenceFileId: string
      deliveryCertificateFileId: string | null
    }
  | {
      action: "RECORD_BUYER_RECEIPT"
      milestoneId: string
      buyerReceivedWeight: number | string
      buyerEvidenceFileId: string
      deliveryCertificateFileId: string | null
    }
  | {
      action: "CONFIRM_CLEAN_RECEIPT"
      milestoneId: string
      declarationNumber: number
      sellerDeclaredWeight: number | string
      buyerReceivedWeight: number | string
    }
  | {
      action: "FLAG_MILESTONE_ISSUE"
      milestoneId: string
      declarationNumber: number
      sellerDeclaredWeight: number | string
      buyerReceivedWeight: number | string
      issueType: IssueType
    }
  | {
      action: "CONTEST_MILESTONE_FLAG"
      milestoneId: string
      sellerDeclaredWeight: number | string
      buyerReceivedWeight: number | string
      issueType: IssueType
    }

/** The `ACTION|field|...` string of the §3.2.3 recipe table. */
export function consentContent(parts: Parts) {
  switch (parts.action) {
    case "DECLARE_SELLER_WEIGHT":
      return [
        parts.action,
        uuid(parts.milestoneId),
        kg3(parts.sellerDeclaredWeight),
        uuid(parts.sellerEvidenceFileId),
        uuid(parts.deliveryCertificateFileId),
      ].join("|")
    case "RECORD_BUYER_RECEIPT":
      return [
        parts.action,
        uuid(parts.milestoneId),
        kg3(parts.buyerReceivedWeight),
        uuid(parts.buyerEvidenceFileId),
        uuid(parts.deliveryCertificateFileId),
      ].join("|")
    case "CONFIRM_CLEAN_RECEIPT":
      return [
        parts.action,
        uuid(parts.milestoneId),
        String(parts.declarationNumber),
        kg3(parts.sellerDeclaredWeight),
        kg3(parts.buyerReceivedWeight),
      ].join("|")
    case "FLAG_MILESTONE_ISSUE":
      return [
        parts.action,
        uuid(parts.milestoneId),
        String(parts.declarationNumber),
        kg3(parts.sellerDeclaredWeight),
        kg3(parts.buyerReceivedWeight),
        parts.issueType,
      ].join("|")
    case "CONTEST_MILESTONE_FLAG":
      return [
        parts.action,
        uuid(parts.milestoneId),
        kg3(parts.sellerDeclaredWeight),
        kg3(parts.buyerReceivedWeight),
        parts.issueType,
      ].join("|")
  }
}

export async function sha256Hex(text: string) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(text)
  )
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("")
}

/** ConsentInput for a milestone action. */
export async function milestoneConsent(parts: Parts) {
  return {
    statementVersion: milestoneStatementVersion(parts.action),
    presentedContentHash: await sha256Hex(consentContent(parts)),
  }
}
