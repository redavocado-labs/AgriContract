import type { Listing } from "./api/listings"

const statusLabels: Record<string, string> = {
  AVAILABLE: "Đang mở bán",
  PARTIALLY_COMMITTED: "Đã giữ một phần",
  CLOSED: "Đã đóng",
  PAUSED: "Tạm dừng",
  DRAFT: "Bản nháp",
}

export function listingStatusLabel(status: string) {
  const label = Object.hasOwn(statusLabels, status) ? statusLabels[status] : undefined
  return label ?? "Chưa xác định"
}

export function canReserveListing(listing: Listing) {
  return (
    (listing.status === "AVAILABLE" || listing.status === "PARTIALLY_COMMITTED") &&
    listing.commodity === "COFFEE" &&
    listing.declaredQualitySpec != null &&
    listing.quantityAvailable != null &&
    listing.minOrderQuantity != null &&
    listing.quantityAvailable > 0 &&
    listing.quantityAvailable >= listing.minOrderQuantity
  )
}

export function listingQuantity(value: number | null | undefined) {
  return value == null ? "Chưa có thông tin" : `${value.toLocaleString("vi-VN")} kg`
}

export function listingPriceUnit(unit: string | null | undefined) {
  return unit === "KG" ? "₫/kg" : "₫"
}
