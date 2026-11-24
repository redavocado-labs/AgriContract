import { useQuery } from "@tanstack/react-query"
import { Link, useSearchParams } from "react-router-dom"

import { searchMarketplace, type MarketplaceListing } from "@/features/listings/api/listings"
import { listingPriceUnit, listingQuantity, listingStatusLabel } from "@/features/listings/presentation"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Banner } from "@/shared/ui/banner"
import { EmptyState } from "@/shared/ui/empty-state"
import { Footer } from "@/shared/ui/footer"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { Pagination } from "@/shared/ui/pagination"
import { StatusBadge } from "@/shared/ui/status-badge"
import { PublicNav } from "./layouts/public-nav"

const scoreOptions = ["80", "85", "90"]

function ListingCard({ listing, signedIn }: { listing: MarketplaceListing; signedIn: boolean }) {
  const name = listing.productName ?? "Nguồn hàng"
  const reputation = signedIn ? listing.sellerReputation : null
  return (
    <article className="flex min-w-0 flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h2 className="break-words text-lg font-medium">{name}</h2>
        <StatusBadge variant="neutral">{listingStatusLabel(listing.status)}</StatusBadge>
      </div>
      {listing.regionText && <p className="text-sm text-text-secondary">{listing.regionText}</p>}
      <MoneyDisplay amount={listing.askingUnitPrice} unit={listingPriceUnit(listing.unitOfMeasure)} />
      <dl className="space-y-1 text-sm">
        <div className="flex flex-wrap justify-between gap-x-2">
          <dt className="text-text-secondary">Khả dụng</dt>
          <dd>{listingQuantity(listing.quantityAvailable)}</dd>
        </div>
        <div className="flex flex-wrap justify-between gap-x-2">
          <dt className="text-text-secondary">Đặt tối thiểu</dt>
          <dd>{listingQuantity(listing.minOrderQuantity)}</dd>
        </div>
      </dl>
      {reputation && (
        <p className="text-sm text-text-secondary">
          {reputation.score === null ? "Chưa có điểm uy tín" : `Điểm uy tín ${reputation.score}/100`}
          {reputation.locked && " · Đang bị khoá giao dịch"}
        </p>
      )}
      <div className="mt-auto flex flex-wrap justify-between gap-x-3 gap-y-2 pt-2 text-sm text-primary">
        <Link className="underline" to={`/reputation/${encodeURIComponent(listing.sellerId)}`}>
          Xem uy tín bên bán
        </Link>
        <Link
          className="underline"
          aria-label={`Xem chi tiết ${name}`}
          to={`/listings/${encodeURIComponent(listing.listingId)}`}
        >
          Xem chi tiết
        </Link>
      </div>
    </article>
  )
}

export function MarketplacePage() {
  const viewerId = useAuthStore((state) => state.authenticated ? state.userId : undefined)
  const [params, setParams] = useSearchParams()
  const requestedPage = Number(params.get("page") ?? "1")
  const page = Number.isInteger(requestedPage) && requestedPage >= 1 && requestedPage <= 10_001
    ? requestedPage : 1
  const requestedScore = params.get("minSellerScore") ?? ""
  const score = viewerId && scoreOptions.includes(requestedScore) ? requestedScore : ""
  const query = useQuery({
    queryKey: ["marketplace", viewerId, page, score],
    queryFn: ({ signal }) => searchMarketplace(page - 1, viewerId, score === "" ? undefined : Number(score), signal),
    retry: false,
  })

  function setFilter(nextPage: number, nextScore: string) {
    const next = new URLSearchParams()
    if (nextPage > 1) next.set("page", String(nextPage))
    if (nextScore) next.set("minSellerScore", nextScore)
    setParams(next)
  }

  return (
    <div className="flex min-h-screen flex-col bg-page-bg text-text-primary">
      <title>Sàn nguồn hàng — AgriContract</title>
      <PublicNav />
      <main className="mx-auto w-full max-w-[1200px] flex-1 space-y-5 px-4 py-8 sm:px-8">
        <h1 className="text-[26px] font-medium">Sàn nguồn hàng</h1>
        {viewerId ? (
          <div className="space-y-2 rounded-xl border border-border bg-surface p-4">
            <label className="flex flex-wrap items-center gap-2 text-sm">
              Điểm uy tín tối thiểu
              <select
                className="rounded-sm border border-border bg-surface px-3 py-2"
                value={score}
                onChange={(event) => setFilter(1, event.target.value)}
                aria-describedby="marketplace-score-help"
              >
                <option value="">Tất cả</option>
                {scoreOptions.map((value) => <option key={value}>{value}</option>)}
              </select>
            </label>
            <p id="marketplace-score-help" className="text-sm text-text-secondary">
              Bộ lọc áp dụng trên từng trang. Nguồn hàng chưa có điểm uy tín vẫn xuất hiện.
            </p>
          </div>
        ) : (
          <Banner>
            <span><Link className="underline" to="/login">Đăng nhập</Link> để xem uy tín bên bán và gửi đề nghị hợp đồng.</span>
          </Banner>
        )}
        <p className="text-sm text-text-secondary">
          Tìm kiếm theo tên, lọc theo vùng hoặc rủi ro vùng trồng và sắp xếp tuỳ chọn chưa khả dụng.
        </p>

        {query.isPending && <p role="status">Đang tải nguồn hàng…</p>}
        {query.isError && (
          <EmptyState
            role="alert"
            variant="error"
            title="Chưa tải được nguồn hàng"
            description="Vui lòng thử lại để cập nhật danh sách."
            actionLabel="Thử lại"
            onAction={() => void query.refetch()}
          />
        )}
        {query.isSuccess && (
          <>
            {query.data.reputationStatus === "UNAVAILABLE" && (
              <Banner role="status" variant="warning">
                Chưa đọc được điểm uy tín của một số bên bán. Các nguồn hàng đó vẫn xuất hiện.
              </Banner>
            )}
            {query.data.reputationStatus === "SIGN_IN_REQUIRED" && viewerId && (
              <Banner role="status" variant="warning">
                Phiên này chưa xem được điểm uy tín của bên bán.
              </Banner>
            )}
            {query.data.content.length ? (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {query.data.content.map((listing) => <ListingCard key={listing.listingId} listing={listing} signedIn={Boolean(viewerId)} />)}
              </div>
            ) : (
              <EmptyState
                title={score ? "Trang này chưa có nguồn hàng phù hợp" : "Chưa có nguồn hàng trên trang này"}
                description={score ? "Thử trang khác hoặc chọn mức điểm thấp hơn." : "Khi có nguồn hàng mở bán, thông tin sẽ xuất hiện tại đây."}
                {...(page > 1 ? {
                  actionLabel: "Về trang đầu",
                  onAction: () => setFilter(1, score),
                } : {})}
              />
            )}
            {query.data.totalPages > 1 && page <= query.data.totalPages && (
              <Pagination page={page} totalPages={query.data.totalPages} onPageChange={(next) => setFilter(next, score)} />
            )}
          </>
        )}
      </main>
      <Footer />
    </div>
  )
}
