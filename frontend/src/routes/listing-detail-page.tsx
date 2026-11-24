import { useQuery } from "@tanstack/react-query"
import { Link, useParams } from "react-router-dom"
import { z } from "zod"

import { capabilities } from "@/features/contracts/api/capabilities"
import { getPublicListing, type Listing } from "@/features/listings/api/listings"
import { canReserveListing, listingPriceUnit, listingQuantity, listingStatusLabel } from "@/features/listings/presentation"
import { useOrganizationPermissions } from "@/features/organization"
import { parseApiError } from "@/shared/api/errors"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Banner } from "@/shared/ui/banner"
import { buttonVariants } from "@/shared/ui/button"
import { EmptyState } from "@/shared/ui/empty-state"
import { Footer } from "@/shared/ui/footer"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { StatusBadge } from "@/shared/ui/status-badge"
import { PublicNav } from "./layouts/public-nav"

function OfferAction({ listing }: { listing: Listing }) {
  const { authenticated, roles, userId } = useAuthStore()
  const { canCreateOrSign, profile } = useOrganizationPermissions()
  if (!capabilities.create.enabled || !canReserveListing(listing))
    return <Banner variant="info">Nguồn hàng này hiện chưa nhận đề nghị hợp đồng.</Banner>
  if (!authenticated) {
    return (
      <div className="flex flex-wrap gap-2">
        <Link to="/login" className={buttonVariants()}>Đăng nhập để gửi đề nghị</Link>
        <Link to="/register" className={buttonVariants({ variant: "outline" })}>Đăng ký doanh nghiệp</Link>
      </div>
    )
  }
  if (!roles.includes("BUYER") || userId === listing.sellerId) {
    return <Banner variant="info">Chỉ bên mua mới có thể gửi đề nghị cho nguồn hàng này.</Banner>
  }
  if (profile.isFetching) return <p role="status">Đang kiểm tra hồ sơ doanh nghiệp…</p>
  if (profile.isError) {
    return (
      <Banner variant="warning" role="alert" actionLabel="Thử lại" onAction={() => void profile.refetch()}>
        Chưa kiểm tra được hồ sơ doanh nghiệp.
      </Banner>
    )
  }
  if (!canCreateOrSign) {
    return (
      <div className="space-y-2">
        <Banner variant="warning">Cần hồ sơ doanh nghiệp đã xác minh để gửi đề nghị.</Banner>
        <Link to="/account" className="text-sm text-primary underline">Xem hồ sơ doanh nghiệp</Link>
      </div>
    )
  }
  return (
    <Link to={`/contracts/new?listingId=${encodeURIComponent(listing.listingId)}`} className={buttonVariants()}>
      Gửi đề nghị
    </Link>
  )
}

const unknown = "Chưa có thông tin"

function ListingDetails({ listing }: { listing: Listing }) {
  const spec = listing.declaredQualitySpec
  return (
    <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <section className="space-y-4" aria-labelledby="listing-title">
        <div className="flex flex-wrap items-center gap-3">
          <h1 id="listing-title" className="break-words text-2xl font-medium">{listing.productName ?? "Nguồn hàng"}</h1>
          <StatusBadge variant="neutral">{listingStatusLabel(listing.status)}</StatusBadge>
        </div>
        {listing.regionText && <p className="text-text-secondary">{listing.regionText}</p>}
        <h2 className="text-lg font-medium">Thông tin nguồn hàng</h2>
        <KeyValueList className="[&>div]:gap-3 [&>div>span:last-child]:break-words [&>div>span:last-child]:text-right" items={[
          { label: "Khối lượng chào bán", value: listingQuantity(listing.offeredQuantity) },
          { label: "Khối lượng khả dụng", value: listingQuantity(listing.quantityAvailable) },
          { label: "Đặt tối thiểu", value: listingQuantity(listing.minOrderQuantity) },
          { label: "Địa điểm kho", value: listing.warehouseLocation || unknown },
          { label: "Ngày dự kiến có hàng", value: listing.estimatedAvailabilityDate || unknown },
          { label: "Đóng gói", value: listing.packagingDeclaration || unknown },
          { label: "Niên vụ", value: listing.cropYear ?? unknown },
          { label: "Mùa thu hoạch", value: listing.harvestSeason || unknown },
        ]} />
        <h2 className="text-lg font-medium">Chất lượng do bên bán khai báo</h2>
        {spec ? (
          <KeyValueList items={[
            { label: "Loại cà phê", value: spec.type === "ARABICA" ? "Arabica" : "Robusta" },
            { label: "Độ ẩm", value: `${spec.moisturePercent}%` },
            { label: "Tạp chất", value: `${spec.foreignMatterPercent}%` },
            { label: "Hạt đen, vỡ", value: `${spec.blackBrokenBeansPercent}%` },
          ]} />
        ) : <p className="text-sm text-text-secondary">Chưa có khai báo chất lượng.</p>}
        {listing.certifications && listing.certifications.length > 0 && (
          <p className="break-words text-sm text-text-secondary">
            Chứng nhận do bên bán khai báo: {listing.certifications.join(", ")}
          </p>
        )}
        <h2 className="text-lg font-medium">Vùng trồng</h2>
        <p className="text-sm text-text-secondary">
          {listing.registryEntryIds?.length ? `${listing.registryEntryIds.length} vùng trồng đã được khai báo. ` : ""}
          Bản đồ và kết quả đánh giá rủi ro vùng trồng chưa có trên trang này.
        </p>
      </section>
      <aside className="space-y-5">
        <section className="space-y-3 rounded-xl border border-border bg-surface p-5">
          <h2 className="text-lg font-medium">Giá chào bán</h2>
          <MoneyDisplay amount={listing.askingUnitPrice} unit={listingPriceUnit(listing.unitOfMeasure)} emphasis="large" />
          <p className="text-sm text-text-secondary"><Link className="text-primary underline" to="/prices">Xem bảng giá tham khảo</Link></p>
          <OfferAction listing={listing} />
        </section>
        <section className="space-y-3 rounded-xl border border-border bg-surface p-5">
          <h2 className="text-lg font-medium">Bên bán</h2>
          <Link to={`/reputation/${encodeURIComponent(listing.sellerId)}`} className="text-sm text-primary underline">
            Xem uy tín bên bán
          </Link>
        </section>
      </aside>
    </div>
  )
}

export function ListingDetailPage() {
  const { id = "" } = useParams()
  const validId = z.guid().safeParse(id).success
  const query = useQuery({
    queryKey: ["public-listing", id],
    queryFn: ({ signal }) => getPublicListing(id, signal),
    enabled: validId,
    retry: false,
  })
  const failure = query.isError ? parseApiError(query.error) : undefined
  const missing = !validId || (failure?.status === 404 && failure.code === "LISTING_NOT_FOUND")
  return (
    <div className="flex min-h-screen flex-col bg-page-bg text-text-primary">
      <title>Chi tiết nguồn hàng — AgriContract</title>
      <PublicNav />
      <main className="mx-auto w-full max-w-[1200px] flex-1 space-y-5 px-4 py-8 sm:px-8">
        <Link to="/listing" className="text-sm text-primary underline">Về sàn nguồn hàng</Link>
        {missing && (
          <section className="space-y-4">
            <h1 className="text-2xl font-medium">Không tìm thấy nguồn hàng</h1>
            <p>Kiểm tra đường dẫn hoặc chọn một nguồn hàng khác trên sàn.</p>
          </section>
        )}
        {!missing && query.isPending && <p role="status">Đang tải nguồn hàng…</p>}
        {!missing && query.isError && (
          <EmptyState
            role="alert"
            variant="error"
            title="Chưa tải được nguồn hàng"
            description="Vui lòng thử lại để cập nhật thông tin nguồn hàng."
            actionLabel="Thử lại"
            onAction={() => void query.refetch()}
          />
        )}
        {query.isSuccess && <ListingDetails listing={query.data} />}
      </main>
      <Footer />
    </div>
  )
}
