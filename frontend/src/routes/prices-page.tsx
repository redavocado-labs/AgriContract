import { useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { getPriceItems, getPriceQuote } from "@/features/listings/api/public-catalog"
import { parseApiError } from "@/shared/api/errors"
import { EmptyState } from "@/shared/ui/empty-state"
import { Footer } from "@/shared/ui/footer"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { StatusBadge } from "@/shared/ui/status-badge"
import { PublicNav } from "./layouts/public-nav"

const provinces = [
  { slug: "dak-lak", label: "Đắk Lắk" },
  { slug: "lam-dong", label: "Lâm Đồng" },
  { slug: "dak-nong", label: "Đắk Nông" },
  { slug: "gia-lai", label: "Gia Lai" },
]

const dateTime = new Intl.DateTimeFormat("vi-VN", {
  dateStyle: "short", timeStyle: "short", timeZone: "Asia/Ho_Chi_Minh",
})
const date = new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeZone: "Asia/Ho_Chi_Minh" })

export function PricesPage() {
  const [province, setProvince] = useState("dak-lak")
  const [selectedItem, setSelectedItem] = useState("")
  const items = useQuery({
    queryKey: ["price-items", province],
    queryFn: ({ signal }) => getPriceItems(province, signal),
    retry: false,
  })
  const activeItem = items.data?.find((item) => item.itemSlug === selectedItem) ?? items.data?.[0]
  const itemSlug = activeItem?.itemSlug ?? ""
  const quote = useQuery({
    queryKey: ["price-quote", province, itemSlug],
    queryFn: ({ signal }) => getPriceQuote(province, itemSlug, signal),
    enabled: Boolean(itemSlug),
    retry: false,
  })
  const failure = quote.isError ? parseApiError(quote.error) : undefined
  const missingQuote = failure?.status === 404 && failure.code === "PRICE_NOT_FOUND"

  return (
    <div className="flex min-h-screen flex-col bg-page-bg text-text-primary">
      <title>Bảng giá tham khảo — AgriContract</title>
      <PublicNav />
      <main className="mx-auto w-full max-w-[1000px] flex-1 space-y-5 px-4 py-8 sm:px-8">
        <header>
          <h1 className="text-2xl font-medium">Bảng giá tham khảo</h1>
          <p className="mt-1 text-sm text-text-secondary">Giá tham khảo cho cà phê. Giá giao dịch do hai bên thỏa thuận trong hợp đồng.</p>
        </header>
        <div className="flex flex-wrap gap-4">
          <label className="flex flex-wrap items-center gap-2 text-sm">
            Tỉnh
            <select
              className="rounded-sm border border-border bg-surface px-3 py-2"
              value={province}
              onChange={(event) => {
                setProvince(event.target.value)
                setSelectedItem("")
              }}
            >
              {provinces.map(({ slug, label }) => <option key={slug} value={slug}>{label}</option>)}
            </select>
          </label>
          {items.isSuccess && items.data.length > 0 && (
            <label className="flex flex-wrap items-center gap-2 text-sm">
              Mặt hàng
              <select
                className="max-w-full rounded-sm border border-border bg-surface px-3 py-2"
                value={itemSlug}
                onChange={(event) => setSelectedItem(event.target.value)}
              >
                {items.data.map((item) => <option key={item.itemSlug} value={item.itemSlug}>{item.itemName}</option>)}
              </select>
            </label>
          )}
        </div>

        {items.isPending && <p role="status">Đang tải mặt hàng của tỉnh…</p>}
        {items.isError && (
          <EmptyState
            role="alert"
            variant="error"
            title="Chưa tải được mặt hàng"
            description="Vui lòng thử lại để tải danh sách mặt hàng."
            actionLabel="Thử lại"
            onAction={() => void items.refetch()}
          />
        )}
        {items.isSuccess && !activeItem && (
          <EmptyState title="Chưa có giá cho tỉnh này" description="Chọn tỉnh khác để xem giá đã được ghi nhận." />
        )}
        {items.isSuccess && activeItem && quote.isPending && <p role="status">Đang tải giá tham khảo…</p>}
        {items.isSuccess && activeItem && missingQuote && (
          <EmptyState title="Chưa có giá cho mặt hàng này" description="Hệ thống chưa tìm thấy báo giá cho lựa chọn hiện tại." />
        )}
        {items.isSuccess && activeItem && quote.isError && !missingQuote && (
          <EmptyState
            role="alert"
            variant="error"
            title="Chưa tải được giá tham khảo"
            description="Vui lòng thử lại để cập nhật báo giá."
            actionLabel="Thử lại"
            onAction={() => void quote.refetch()}
          />
        )}
        {items.isSuccess && quote.isSuccess && (
          <section className="space-y-4 rounded-xl border border-border bg-surface p-5">
            <h2 className="text-lg font-medium">{quote.data.itemName} · {quote.data.province}</h2>
            <div className="flex flex-wrap items-center gap-3">
              <MoneyDisplay amount={quote.data.price} unit="₫/kg" emphasis="large" />
              {quote.data.stale && <StatusBadge variant="warning">Giá này đã cũ</StatusBadge>}
            </div>
            <KeyValueList items={[
              { label: "Nguồn", value: quote.data.source === "VNSAT_SCRAPE" ? "VNSAT" : (quote.data.referenceSource || "Điều phối viên nhập") },
              { label: "Ngày ghi nhận giá", value: <time dateTime={quote.data.capturedAt}>{date.format(new Date(quote.data.capturedAt))}</time> },
              { label: "Hệ thống cập nhật", value: <time dateTime={quote.data.ingestedAt}>{dateTime.format(new Date(quote.data.ingestedAt))}</time> },
            ]} />
          </section>
        )}
        <p className="text-sm text-text-secondary">Biểu đồ và lịch sử giá chưa khả dụng.</p>
      </main>
      <Footer />
    </div>
  )
}
