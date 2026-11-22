import { parseApiError } from "@/shared/api/errors"
export { vietnamTime } from "@/shared/lib/vietnam-time"
import { capabilities } from "../api/capabilities"
import type { Contract, ParticipantRole } from "./contract"

export const statusLabels: Record<string, string> = {
  OFFERED: "Đã gửi đề nghị",
  NEGOTIATING: "Đang đàm phán",
  SIGNED: "Đã ký — chờ kích hoạt",
  ACTIVE: "Đang hiệu lực",
  SETTLED: "Đã tất toán",
  WITHDRAWN: "Đã rút đề nghị",
  TERMINATED: "Đã chấm dứt",
  SUPERSEDED: "Đã thay thế",
  ACTIVATION_FAILED: "Kích hoạt thất bại",
  ACTIVATION_REFUND_PENDING: "Đang hoàn cọc",
  REPLACEMENT_PENDING: "Đang thay thế",
  SUPERSEDE_REFUND_PENDING: "Đang hoàn tiền để thay thế",
}
// Values the contract-service ContractStatus enum accepts as a list filter today.
export const filterStatuses = [
  "OFFERED",
  "NEGOTIATING",
  "SIGNED",
  "ACTIVE",
  "SETTLED",
  "ACTIVATION_REFUND_PENDING",
  "ACTIVATION_FAILED",
]
export const PRE_SIGNATURE = ["OFFERED", "NEGOTIATING"]
export function participantRole(
  contract: Contract,
  userId: string,
  roles: readonly string[]
): ParticipantRole | null {
  if (contract.buyerId === userId && roles.includes("BUYER")) return "BUYER"
  if (contract.sellerId === userId && roles.includes("SELLER")) return "SELLER"
  return null
}
/**
 * Terms can be revised until the first signature. ContractResponse does not
 * expose signatures, so the server answers CONTRACT_TERMS_FROZEN after it.
 */
export function canNegotiate(contract: Contract, verified: boolean) {
  return (
    capabilities.negotiate.enabled &&
    verified &&
    contract.generation === "coffee" &&
    PRE_SIGNATURE.includes(contract.status)
  )
}
/** Withdraw before the second signature; legacy rows included (#233). */
export function canWithdraw(contract: Contract, verified: boolean) {
  return (
    capabilities.withdraw.enabled &&
    verified &&
    PRE_SIGNATURE.includes(contract.status)
  )
}
export function activationMessage(status: string) {
  switch (status) {
    case "SIGNED":
      return "Đã ký hai bên — chờ xử lý và khóa tiền cọc. Hợp đồng chưa được kích hoạt."
    case "ACTIVE":
      return "Đã kích hoạt theo trạng thái máy chủ."
    case "ACTIVATION_FAILED":
      return "Kích hoạt thất bại. Liên hệ bộ phận vận hành để được hỗ trợ."
    case "ACTIVATION_REFUND_PENDING":
      return "Đang hoàn tiền cọc theo trạng thái máy chủ."
    case "OFFERED":
    case "NEGOTIATING":
      return "Hợp đồng chưa có hiệu lực. Điều khoản sửa được tới khi có chữ ký đầu tiên; cần đủ hai chữ ký để chuyển sang Đã ký."
    case "WITHDRAWN":
      return "Đề nghị đã được rút trước khi đủ hai chữ ký. Không bên nào bị phạt."
    default:
      return (
        "Trạng thái hợp đồng: " + (statusLabels[status] ?? "Chưa nhận biết")
      )
  }
}
export function documentMessage(status?: string) {
  if (status === "READY") return "PDF cuối đã sẵn sàng"
  if (status === "PENDING") return "PDF cuối đang được tạo"
  if (status === "FAILED") return "Tạo PDF cuối chưa thành công"
  return "Chưa thể xác định trạng thái PDF cuối"
}
export const canDownloadDocument = (status?: string) => status === "READY"
export function pollInterval(
  status: string | undefined,
  startedAt: number,
  now: number,
  error: boolean
) {
  return status === "SIGNED" && !error && now - startedAt < 120_000
    ? 5_000
    : false
}
export function decimalMoney(value: unknown): string | null {
  if (typeof value === "string" && /^\d+(\.\d{1,2})?$/.test(value)) return value
  // Legacy JSON numbers cannot recover lost fractional precision. Fail closed for unsafe amounts.
  if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0)
    return String(value)
  return null
}
const errorMessages: Record<string, string> = {
  CONTRACT_TERMS_FROZEN:
    "Điều khoản đã khóa sau chữ ký đầu tiên. Vui lòng tải lại hợp đồng.",
  CONTRACT_NOT_NEGOTIABLE: "Trạng thái hợp đồng không cho phép sửa điều khoản.",
  CONTRACT_NOT_WITHDRAWABLE:
    "Không thể rút đề nghị: hợp đồng đã đủ hai chữ ký hoặc đã kết thúc.",
  PRESENTED_CONTENT_STALE:
    "Nội dung bạn đang xem đã cũ. Tải lại hợp đồng rồi thao tác lại.",
  RESERVED_QUANTITY_MISMATCH:
    "Tổng khối lượng các đợt khác số lượng đã giữ hàng. Hai bên cần sửa lịch giao trước khi ký.",
  DELIVERY_SCHEDULE_TOO_SOON:
    "Có đợt giao quá sát ngày để kịp khoá tiền. Hai bên sửa lịch giao rồi ký lại.",
  VALIDATION_ERROR: "Dữ liệu chưa hợp lệ. Vui lòng kiểm tra lại các trường.",
  VALIDATION_FAILED: "Dữ liệu chưa hợp lệ. Vui lòng kiểm tra lại các trường.",
  PRESENTED_CONTENT_HASH_MISMATCH:
    "Điều khoản vừa thay đổi. Tải lại và đọc lại trước khi ký.",
  OTP_TERMS_CHANGED:
    "Điều khoản đã thay đổi. Mã OTP cũ không còn sử dụng được.",
  CONSENT_STATEMENT_OUTDATED:
    "Cam kết đã có phiên bản mới. Vui lòng tải lại trước khi tiếp tục.",
  STEP_UP_REQUIRED: "Bạn cần đăng nhập lại để xác nhận thao tác ký.",
  SIGNER_NOT_VERIFIED: "Hồ sơ chưa được xác minh nên chưa thể ký.",
  SIGNER_AUTHORIZATION_EXPIRED: "Ủy quyền đại diện đã hết hạn.",
  SIGNER_LOCKED: "Tài khoản ký đang bị khóa.",
  SIGNER_EMAIL_MISSING: "Tài khoản chưa có email nhận OTP.",
  SIGNER_PROFILE_NOT_FOUND: "Chưa tìm thấy hồ sơ người ký.",
  OTP_INVALID: "Mã OTP không đúng. Vui lòng kiểm tra lại email.",
  OTP_EXPIRED: "Mã OTP đã hết hạn. Vui lòng gửi mã mới.",
  OTP_ATTEMPTS_EXHAUSTED: "Đã hết số lần thử. Vui lòng gửi mã mới.",
  OTP_CHALLENGE_NOT_ACTIVE: "Mã OTP không còn hiệu lực. Vui lòng gửi mã mới.",
  OTP_RESEND_TOO_SOON: "Chưa đến thời gian gửi lại mã. Vui lòng chờ.",
  OTP_RESEND_LIMIT_REACHED: "Đã đạt giới hạn gửi mã. Vui lòng thử lại sau.",
  CONTRACT_ALREADY_SIGNED_BY_ACTOR:
    "Bạn đã ký hợp đồng này. Vui lòng tải lại trạng thái.",
  CONTRACT_NOT_SIGNABLE: "Trạng thái hợp đồng không cho phép ký.",
  CONTRACT_HAS_NO_COFFEE_TERMS:
    "Hợp đồng cũ chỉ được xem, không hỗ trợ ký theo quy trình cà phê.",
  COUNTERPARTY_NOT_ELIGIBLE: "Bên còn lại hiện chưa đủ điều kiện ký.",
  IDEMPOTENCY_KEY_REUSED:
    "Yêu cầu xung đột với thao tác trước. Tải lại trạng thái trước khi tiếp tục.",
  OTP_EMAIL_FAILED: "Không gửi được email OTP. Vui lòng thử lại.",
  OTP_EMAIL_TIMEOUT:
    "Gửi email OTP quá thời gian chờ. Vui lòng kiểm tra trước khi gửi lại.",
  NOTIFICATION_UNREACHABLE: "Dịch vụ email chưa sẵn sàng.",
  LISTING_VERSION_MISMATCH:
    "Nguồn hàng đã thay đổi. Tải lại dữ liệu trước khi tạo hợp đồng.",
  INSUFFICIENT_LISTING_QUANTITY:
    "Số lượng hiện không đáp ứng đơn hàng. Vui lòng tải lại nguồn hàng.",
}
export function contractError(error: unknown) {
  const { status, code } = parseApiError(error)
  if (code && errorMessages[code]) return errorMessages[code]
  if (status === 401) return "Phiên đăng nhập cần được xác thực lại."
  if (status === 403)
    return "Bạn không có quyền thực hiện thao tác với hợp đồng này."
  if (status === 404)
    return "Không tìm thấy hợp đồng hoặc tài nguyên được yêu cầu."
  if (status === 409)
    return "Dữ liệu đã xung đột. Tải lại hợp đồng; không tự gửi lại thao tác."
  if (status === 400 || status === 422)
    return "Dữ liệu chưa hợp lệ. Vui lòng kiểm tra các điều khoản."
  if (status && status >= 500)
    return "Dịch vụ đang gặp lỗi. Vui lòng thử tải lại sau."
  if (
    error instanceof Error &&
    !status &&
    error.name !== "AxiosError" &&
    error.name !== "ZodError"
  )
    return error.message
  return "Không thể tải dữ liệu hợp đồng. Kiểm tra kết nối và thử lại."
}
