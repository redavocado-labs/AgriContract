import axios from "axios"
import { z } from "zod"
import { apiClient } from "@/shared/api/client"
import { isErrorEnvelope, parseApiError } from "@/shared/api/errors"
import type { AppRole } from "@/shared/auth/auth-store"

const optionalText = z
  .string()
  .nullish()
  .transform((value) => value?.trim() || null)
const profileSchema = z.object({
  userId: z.string().min(1),
  organizationName: optionalText,
  email: optionalText,
  emailVerified: z.boolean().optional().default(false),
  phone: optionalText,
  address: optionalText,
  role: optionalText,
  verificationStatus: optionalText,
  authorizationExpiresAt: optionalText,
})
export type OrganizationProfile = z.infer<typeof profileSchema>
export const organizationFormSchema = z.object({
  role: z.enum(["BUYER", "SELLER"], { error: "Vui lòng chọn loại tài khoản." }),
  organizationName: z.string().trim().min(1, "Vui lòng nhập tên doanh nghiệp."),
  email: z
    .string()
    .trim()
    .min(1, "Vui lòng nhập email nhận thông báo và mã OTP.")
    .email("Email chưa đúng định dạng."),
  phone: z.string().trim(),
  address: z.string().trim(),
})
export type OrganizationFormValues = z.infer<typeof organizationFormSchema>

export class ProfileResponseError extends Error {}
export class RegistrationRoleError extends Error {}

export function parseProfile(
  data: unknown,
  userId: string
): OrganizationProfile {
  const result = profileSchema.safeParse(data)
  if (!result.success || result.data.userId !== userId) {
    throw new ProfileResponseError(
      "Phản hồi hồ sơ không đúng định dạng hoặc không thuộc tài khoản hiện tại."
    )
  }
  return result.data
}

export function isMissingProfile(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  const { response, config } = error
  // Exact user-service ErrorEnvelope, bound to GET /users/me. A generic
  // Gateway/HTML 404 must stay an error, never trigger registration.
  return (
    config?.url === "/api/v1/users/me" &&
    config.method?.toLowerCase() === "get" &&
    response?.status === 404 &&
    isErrorEnvelope(response.data) &&
    response.data.code === "USER_NOT_FOUND"
  )
}

export async function getOrganizationProfile(
  userId: string,
  signal?: AbortSignal
): Promise<OrganizationProfile | null> {
  try {
    const { data } = await apiClient.get("/api/v1/users/me", { signal })
    return parseProfile(data, userId)
  } catch (error) {
    if (isMissingProfile(error)) return null
    throw error
  }
}

export function registrationRoleProblem(
  roles: readonly AppRole[],
  selected?: string
) {
  // Backend picks the first recognized role; multiple roles are ambiguous.
  const [role] = roles
  if (roles.length !== 1 || (role !== "BUYER" && role !== "SELLER")) {
    return "Tài khoản cần được quản trị viên cấp đúng một vai trò Bên mua hoặc Bên bán trước khi tạo hồ sơ. Lựa chọn trên form không tự cấp quyền."
  }
  if (selected && selected !== role)
    return "Loại tài khoản đã chọn không khớp vai trò đăng nhập. Vui lòng chọn đúng vai trò hoặc liên hệ quản trị viên."
  return null
}

export function registrationRequest(
  values: OrganizationFormValues,
  roles: readonly AppRole[]
) {
  const parsed = organizationFormSchema.parse(values)
  const problem = registrationRoleProblem(roles, parsed.role)
  if (problem) throw new RegistrationRoleError(problem)
  // RegisterUserRequest: organizationName, email, phone, address. Role comes from JWT.
  return {
    organizationName: parsed.organizationName,
    email: parsed.email,
    phone: parsed.phone || null,
    address: parsed.address || null,
  }
}

export interface RegistrationResult {
  profile: OrganizationProfile
  /** Present for a new profile only: whether the first verification code was emailed. */
  verificationEmailSent?: boolean
  verificationCodeExpiresAt?: string
}

export async function registerOrganization(
  values: OrganizationFormValues,
  userId: string,
  roles: readonly AppRole[]
): Promise<RegistrationResult> {
  const { data } = await apiClient.post(
    "/api/v1/users/register",
    registrationRequest(values, roles)
  )
  const extra = z
    .object({
      verificationEmailSent: z.boolean().optional(),
      verificationCodeExpiresAt: z.string().nullish(),
    })
    .parse(data)
  return {
    profile: parseProfile(data, userId),
    verificationEmailSent: extra.verificationEmailSent,
    verificationCodeExpiresAt: extra.verificationCodeExpiresAt ?? undefined,
  }
}

export async function requestEmailVerification() {
  const { data } = await apiClient.post("/api/v1/users/me/email-verification")
  return z.object({ expiresAt: z.string() }).parse(data)
}

export async function confirmEmailVerification(code: string, userId: string) {
  if (!/^\d{6}$/.test(code))
    throw new Error("Nhập đủ 6 chữ số của mã xác minh.")
  const { data } = await apiClient.post(
    "/api/v1/users/me/email-verification/confirm",
    { code }
  )
  return parseProfile(data, userId)
}

const userErrorMessages: Record<string, string> = {
  EMAIL_ALREADY_CLAIMED:
    "Email này đã được một tài khoản khác xác minh. Vui lòng dùng email khác.",
  EMAIL_ALREADY_VERIFIED: "Email đã được xác minh. Tải lại hồ sơ để cập nhật.",
  VERIFICATION_CODE_INVALID: "Mã xác minh không đúng. Vui lòng kiểm tra email.",
  VERIFICATION_NOT_REQUESTED: "Chưa có mã xác minh nào. Vui lòng gửi mã mới.",
  VERIFICATION_EXPIRED: "Mã xác minh đã hết hạn. Vui lòng gửi mã mới.",
  VERIFICATION_ATTEMPTS_EXHAUSTED:
    "Đã nhập sai quá số lần cho phép. Vui lòng gửi mã mới.",
  VERIFICATION_RESEND_TOO_SOON: "Vừa gửi mã. Vui lòng chờ ít phút rồi thử lại.",
  VERIFICATION_EMAIL_NOT_SENT:
    "Chưa gửi được email xác minh. Bạn có thể thử gửi lại ngay.",
  VALIDATION_FAILED: "Thông tin chưa hợp lệ. Vui lòng kiểm tra lại các trường.",
  USER_PROFILE_VERSION_MISMATCH:
    "Hồ sơ vừa được cập nhật ở nơi khác. Vui lòng tải lại.",
}

export function organizationErrorMessage(error: unknown) {
  if (
    error instanceof ProfileResponseError ||
    error instanceof RegistrationRoleError
  )
    return error.message
  const parsed = parseApiError(error)
  const knownMessage = userErrorMessages[parsed.code]
  if (knownMessage) return knownMessage
  if (parsed.status === 401)
    return "Phiên đăng nhập cần được xác thực lại. Đang chuyển đến hệ thống đăng nhập."
  if (parsed.status === 403)
    return "Tài khoản chưa được phép thực hiện yêu cầu này. Vui lòng liên hệ quản trị viên."
  if (parsed.status === 404)
    return "Không xác định được nguồn lỗi không tìm thấy. Chưa thể kết luận tài khoản thiếu hồ sơ. Vui lòng thử lại hoặc liên hệ quản trị viên."
  if (parsed.status === 400)
    return "Máy chủ chưa chấp nhận thông tin hồ sơ. Vui lòng kiểm tra dữ liệu và vai trò tài khoản."
  if (parsed.status === 409)
    return "Hồ sơ có thay đổi hoặc xung đột. Vui lòng tải lại thông tin trước khi tiếp tục."
  if (parsed.status >= 500)
    return "Dịch vụ hồ sơ tạm thời không khả dụng. Vui lòng thử lại sau."
  if (error instanceof Error && !parsed.status && !axios.isAxiosError(error))
    return error.message
  return parsed.message
}
