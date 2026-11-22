import { useState } from "react"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { OTPInput } from "@/shared/ui/otp-input"
import { StatusBadge } from "@/shared/ui/status-badge"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import {
  organizationErrorMessage,
  type OrganizationProfile,
} from "../api/profile"
import {
  useConfirmEmailVerification,
  useRequestEmailVerification,
} from "../hooks/use-organization-profile"

/**
 * Contact email verification (user-service #344): OTP for contract signing
 * and notifications only reach a verified address.
 */
export function EmailVerification({
  profile,
}: {
  profile: OrganizationProfile
}) {
  const send = useRequestEmailVerification()
  const confirm = useConfirmEmailVerification()
  const [code, setCode] = useState("")
  if (!profile.email) return null
  const error = send.error ?? confirm.error
  return (
    <section
      id="email-verification"
      className="space-y-4 rounded-md border border-border bg-surface p-5 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base">Email nhận OTP</h2>
        <StatusBadge variant={profile.emailVerified ? "success" : "warning"}>
          {profile.emailVerified ? "Đã xác minh" : "Chưa xác minh"}
        </StatusBadge>
      </div>
      <p className="break-all font-mono text-sm">{profile.email}</p>
      {profile.emailVerified ? (
        <p className="text-sm text-text-secondary">
          Mã OTP ký hợp đồng và thông báo nghiệp vụ được gửi tới email này.
        </p>
      ) : (
        <>
          <Banner variant="warning">
            Cần xác minh email trước khi ký hợp đồng: mã OTP chỉ được gửi tới
            email đã xác minh.
          </Banner>
          {send.data && (
            <p role="status" className="text-sm text-text-secondary">
              Đã gửi mã mới. Mã hết hạn lúc {vietnamTime(send.data.expiresAt)}.
            </p>
          )}
          <div className="space-y-3">
            <p className="text-sm">Nhập mã 6 số trong email</p>
            <fieldset
              disabled={confirm.isPending}
              aria-label="Mã xác minh email"
            >
              <OTPInput value={code} onChange={setCode} />
            </fieldset>
            <div className="flex flex-wrap gap-3">
              <Button
                disabled={code.length !== 6 || confirm.isPending}
                onClick={() =>
                  confirm.mutate(code, { onSettled: () => setCode("") })
                }
              >
                {confirm.isPending ? "Đang xác minh…" : "Xác minh email"}
              </Button>
              <Button
                variant="outline"
                disabled={send.isPending}
                onClick={() => send.mutate()}
              >
                {send.isPending ? "Đang gửi…" : "Gửi lại mã"}
              </Button>
            </div>
          </div>
        </>
      )}
      {error && (
        <Banner role="alert" variant="danger">
          {organizationErrorMessage(error)}
        </Banner>
      )}
    </section>
  )
}
