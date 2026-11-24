import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Link, useParams } from "react-router-dom"
import { z } from "zod"

import { getPublicPartner, getPublicReputationSummary, type ReputationSummary } from "@/features/reputation/api/public-summary"
import { parseApiError } from "@/shared/api/errors"
import { ROLE_LABELS, useAuthStore } from "@/shared/auth/auth-store"
import { login } from "@/shared/auth/keycloak"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { EmptyState } from "@/shared/ui/empty-state"
import { Footer } from "@/shared/ui/footer"
import { StatusBadge } from "@/shared/ui/status-badge"
import { PublicNav } from "./layouts/public-nav"

const lockStatus = {
  ACTIVE: "Đang khoá",
  EXPIRED: "Đã hết hạn",
  UNLOCKED_EARLY: "Đã mở khoá sớm",
}
const date = new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeZone: "Asia/Ho_Chi_Minh" })

function ReputationDetails({ summary }: { summary: ReputationSummary }) {
  return (
    <div className="space-y-5">
      <section className="space-y-3 rounded-xl border border-border bg-surface p-5">
        <h2 className="text-lg font-medium">Điểm uy tín</h2>
        {summary.score === null ? (
          <p className="text-sm text-text-secondary">Chưa có dữ liệu uy tín</p>
        ) : (
          <p className="font-mono text-2xl">{summary.score} / 100</p>
        )}
        {summary.locked && <StatusBadge variant="danger">Đang bị khoá giao dịch</StatusBadge>}
      </section>
      <section className="space-y-3 rounded-xl border border-border bg-surface p-5">
        <h2 className="text-lg font-medium">Lịch sử khoá giao dịch</h2>
        {summary.lockHistory.length === 0 ? (
          <p className="text-sm text-text-secondary">Chưa ghi nhận lần khoá giao dịch nào.</p>
        ) : (
          <ul className="divide-y divide-border">
            {summary.lockHistory.map((lock, index) => (
              <li key={`${lock.lockedAt}-${lock.penalizedRole}-${index}`} className="flex flex-wrap justify-between gap-2 py-3 text-sm">
                <div>
                  <div>{lock.penalizedRole === "BUYER" ? "Bên mua" : "Bên bán"} · {lock.lockDurationDays} ngày</div>
                  <div className="text-text-secondary">
                    <time dateTime={lock.lockedAt}>{date.format(new Date(lock.lockedAt))}</time>
                    {" – "}
                    <time dateTime={lock.lockedUntil}>{date.format(new Date(lock.lockedUntil))}</time>
                  </div>
                </div>
                <StatusBadge variant={lock.status === "ACTIVE" ? "danger" : "neutral"}>{lockStatus[lock.status]}</StatusBadge>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function ReputationSignIn() {
  const [error, setError] = useState(false)
  return (
    <div className="space-y-3">
      <p>Đăng nhập để xem hồ sơ uy tín</p>
      <Button onClick={() => {
        setError(false)
        void login().catch(() => setError(true))
      }}>Đăng nhập</Button>
      {error && <Banner role="alert" variant="danger">Không thể mở hệ thống đăng nhập. Vui lòng thử lại.</Banner>}
    </div>
  )
}

export function PublicReputationPage() {
  const { userId = "" } = useParams()
  const viewerId = useAuthStore((state) => state.authenticated ? state.userId : undefined)
  const authStatus = useAuthStore((state) => state.status)
  const validId = z.guid().safeParse(userId).success
  const enabled = validId && authStatus === "ready" && Boolean(viewerId)
  const query = useQuery({
    queryKey: ["public-reputation", viewerId, userId],
    queryFn: ({ signal }) => getPublicReputationSummary(userId, signal),
    enabled,
    retry: false,
  })
  const partner = useQuery({
    queryKey: ["public-partner", viewerId, userId],
    queryFn: ({ signal }) => getPublicPartner(userId, signal),
    enabled,
    retry: false,
  })
  const status = query.isError ? parseApiError(query.error).status : undefined
  const unauthorized = status === 401 || status === 403

  return (
    <div className="flex min-h-screen flex-col bg-page-bg text-text-primary">
      <title>Hồ sơ uy tín — AgriContract</title>
      <PublicNav />
      <main className="mx-auto w-full max-w-[900px] flex-1 space-y-5 px-4 py-8 sm:px-8">
        <Link to="/listing" className="text-sm text-primary underline">Về sàn nguồn hàng</Link>
        <h1 className="text-2xl font-medium">Hồ sơ uy tín</h1>
        {!validId && <p role="status">Đường dẫn hồ sơ uy tín không hợp lệ.</p>}
        {validId && authStatus === "error" && <p role="alert">Chưa kết nối được hệ thống đăng nhập.</p>}
        {validId && authStatus === "initializing" && <p role="status">Đang kiểm tra phiên đăng nhập…</p>}
        {validId && authStatus === "ready" && !viewerId && <ReputationSignIn />}
        {enabled && query.isPending && <p role="status">Đang tải hồ sơ uy tín…</p>}
        {enabled && query.isError && (
          <EmptyState
            role="alert"
            variant="error"
            title={unauthorized ? "Không có quyền xem hồ sơ uy tín" : "Chưa tải được hồ sơ uy tín"}
            description={unauthorized ? "Vui lòng kiểm tra phiên đăng nhập." : "Vui lòng thử lại để tải hồ sơ."}
            {...(!unauthorized ? { actionLabel: "Thử lại", onAction: () => void query.refetch() } : {})}
          />
        )}
        {enabled && query.isSuccess && (
          <>
            {partner.isSuccess ? (
              <div className="space-y-1">
                <p className="break-words text-lg font-medium">{partner.data.organizationName}</p>
                <p className="text-sm text-text-secondary">
                  {ROLE_LABELS[partner.data.role]}
                  {partner.data.verificationStatus === "VERIFIED" && " · Hồ sơ đã xác minh"}
                </p>
              </div>
            ) : <p className="break-words text-sm text-text-secondary">Mã đối tác {userId}</p>}
            <ReputationDetails summary={query.data} />
          </>
        )}
      </main>
      <Footer />
    </div>
  )
}
