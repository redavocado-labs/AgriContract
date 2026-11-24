import { useState } from "react"
import { Controller, useFieldArray, useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Link, useNavigate, useSearchParams } from "react-router-dom"
import { ContractsLayout } from "@/features/contracts/components/contracts-layout"
import { ContractFailure } from "@/features/contracts/components/contract-state"
import { capabilities } from "@/features/contracts/api/capabilities"
import { createContract, listContracts } from "@/features/contracts/api/contracts"
import {
  offerDefaults,
  offerSchema,
  offerTerms,
  type OfferOutput,
  type OfferValues,
} from "@/features/contracts/schemas/offer"
import { contractError } from "@/features/contracts"
import {
  getListing,
  searchListings,
  type Listing,
} from "@/features/listings/api/listings"
import { canReserveListing } from "@/features/listings/presentation"
import { useOrganizationPermissions } from "@/features/organization"
import { parseApiError } from "@/shared/api/errors"
import { useRecoverableWrite } from "@/shared/api/operation-recovery"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { DatePicker } from "@/shared/ui/date-picker"
import { EmptyState } from "@/shared/ui/empty-state"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { Pagination } from "@/shared/ui/pagination"
import { RecordCard } from "@/shared/ui/record-card"
import { SelectDropdown } from "@/shared/ui/select-dropdown"
import { Skeleton } from "@/shared/ui/skeleton"
import { Stepper } from "@/shared/ui/stepper"
import { StickyFormSummary } from "@/shared/ui/sticky-form-summary"
import { TextInput } from "@/shared/ui/text-input"

const kg = (value: number | null | undefined) =>
  value == null ? "Chưa có thông tin" : `${value.toLocaleString("vi-VN")} kg`

export function CreateContractPage() {
  const [params, setParams] = useSearchParams()
  const listingId = params.get("listingId")
  const { canCreateOrSign } = useOrganizationPermissions()
  const roles = useAuthStore((state) => state.roles)
  const allowed =
    capabilities.create.enabled && canCreateOrSign && roles.includes("BUYER")
  return (
    <ContractsLayout title="Tạo đề nghị hợp đồng cà phê">
      <Stepper
        steps={["Chọn nguồn hàng", "Điều khoản", "Gửi đề nghị"]}
        currentStep={listingId ? 2 : 1}
      />
      {!allowed && (
        <Banner variant="warning">
          Chỉ bên mua có hồ sơ đã xác minh mới gửi được đề nghị hợp đồng.
        </Banner>
      )}
      {listingId ? (
        <OfferForm
          key={listingId}
          listingId={listingId}
          allowed={allowed}
          onChangeListing={() => setParams({})}
        />
      ) : (
        <ListingPicker onPick={(id) => setParams({ listingId: id })} />
      )}
    </ContractsLayout>
  )
}

function ListingPicker({ onPick }: { onPick: (id: string) => void }) {
  const [page, setPage] = useState(0)
  const viewerId = useAuthStore((state) => state.userId)
  const query = useQuery({
    queryKey: ["listings", viewerId, page],
    queryFn: ({ signal }) => searchListings(page, signal),
    enabled: !!viewerId,
    retry: false,
  })
  if (query.isPending)
    return (
      <div
        role="status"
        aria-label="Đang tải nguồn hàng"
        className="grid gap-3 md:grid-cols-2"
      >
        <Skeleton className="h-32" />
        <Skeleton className="h-32" />
      </div>
    )
  if (query.isError)
    return (
      <EmptyState
        variant="error"
        title="Chưa tải được nguồn hàng"
        description={contractError(query.error)}
        actionLabel="Thử lại"
        onAction={() => void query.refetch()}
      />
    )
  const listings = query.data.content.filter(
    (item) => item.sellerId !== viewerId && canReserveListing(item)
  )
  return (
    <section className="space-y-4">
      <p className="text-sm text-text-secondary">
        Chọn một nguồn hàng cà phê đang mở bán. Số lượng khả dụng và đơn giá lấy
        từ máy chủ tại thời điểm chọn.
      </p>
      {listings.length === 0 ? (
        <EmptyState
          title="Chưa có nguồn hàng đang mở bán"
          description="Khi bên bán đăng nguồn hàng cà phê đã được duyệt, nguồn hàng sẽ hiện ở đây."
        />
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {listings.map((item) => (
            <RecordCard
              key={item.listingId}
              code={item.productName ?? "Cà phê"}
              subtitle={item.regionText ?? item.warehouseLocation ?? undefined}
              highlight={
                <MoneyDisplay amount={item.askingUnitPrice} unit="₫/kg" />
              }
              rows={[
                { label: "Khả dụng", value: kg(item.quantityAvailable) },
                { label: "Đặt tối thiểu", value: kg(item.minOrderQuantity) },
              ]}
              chevron
              onClick={() => onPick(item.listingId)}
            />
          ))}
        </div>
      )}
      {query.data.totalPages > 1 && (
        <Pagination
          page={page + 1}
          totalPages={query.data.totalPages}
          onPageChange={(p) => setPage(p - 1)}
        />
      )}
    </section>
  )
}

function OfferForm({
  listingId,
  allowed,
  onChangeListing,
}: {
  listingId: string
  allowed: boolean
  onChangeListing: () => void
}) {
  const viewerId = useAuthStore((state) => state.userId)
  const listing = useQuery({
    queryKey: ["listing", viewerId, listingId],
    queryFn: ({ signal }) => getListing(listingId, signal),
    enabled: !!viewerId,
    retry: false,
  })
  if (listing.isPending)
    return (
      <div role="status" aria-label="Đang tải nguồn hàng" className="space-y-3">
        <Skeleton className="h-32" />
        <Skeleton className="h-64" />
      </div>
    )
  if (listing.isError)
    return (
      <ContractFailure
        error={listing.error}
        retry={() => void listing.refetch()}
      />
    )
  if (listing.data.sellerId === viewerId || !canReserveListing(listing.data))
    return (
      <EmptyState
        title="Nguồn hàng này hiện chưa nhận đề nghị hợp đồng"
        description="Nguồn hàng phải đang mở bán, còn đủ khối lượng tối thiểu và thuộc một bên bán khác."
        actionLabel="Chọn nguồn hàng khác"
        onAction={onChangeListing}
      />
    )
  return (
    <OfferEditor
      listing={listing.data}
      allowed={allowed}
      onChangeListing={onChangeListing}
      reloadListing={() => void listing.refetch()}
    />
  )
}

function OfferEditor({
  listing,
  allowed,
  onChangeListing,
  reloadListing,
}: {
  listing: Listing
  allowed: boolean
  onChangeListing: () => void
  reloadListing: () => void
}) {
  const userId = useAuthStore((state) => state.userId)
  const navigate = useNavigate()
  const cache = useQueryClient()
  const [error, setError] = useState("")
  const [stale, setStale] = useState(false)
  const operation = useRecoverableWrite("create-offer", listing.listingId)
  const {
    register,
    control,
    watch,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<OfferValues, unknown, OfferOutput>({
    resolver: zodResolver(offerSchema),
    defaultValues: offerDefaults(listing),
  })
  const milestones = useFieldArray({ control, name: "milestones" })
  const total = (watch("milestones") ?? []).reduce(
    (sum, m) => sum + (Number(m.committedQuantity) || 0),
    0
  )
  const busy = isSubmitting || operation.busy
  const submit = handleSubmit(async (values) => {
    if (!userId || !allowed) return
    setError("")
    const request = {
      listingId: listing.listingId,
      listingVersionToken: listing.listingVersionToken,
      terms: offerTerms(values),
    }
    let contract
    try {
      contract = await operation.run({
        binding: JSON.stringify(request),
        reconcile: async () => {
          // The list can be reviewed, but its rows cannot be attributed to this command.
          await cache.fetchQuery({
            queryKey: ["contracts", userId, "BUYER", "", 0],
            queryFn: ({ signal }) => listContracts(userId, "BUYER", "", 0, signal),
            retry: false,
            staleTime: 0,
          })
          return "replay"
        },
        send: (key) => createContract(request, key, userId),
      })
    } catch (failure) {
      const { code } = parseApiError(failure)
      setError(contractError(failure))
      if (
        code === "LISTING_VERSION_MISMATCH" ||
        code === "INSUFFICIENT_LISTING_QUANTITY"
      )
        setStale(true)
      return
    }
    if (useAuthStore.getState().userId !== userId) return
    cache.setQueryData(["contract", userId, contract.id], contract)
    void cache.invalidateQueries({ queryKey: ["contracts", userId] }).catch(() => {})
    navigate(`/contracts/${encodeURIComponent(contract.id)}`)
  })
  return (
    <form
      onSubmit={submit}
      noValidate
      className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]"
    >
      <div className="min-w-0 space-y-5">
        <section className="space-y-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg">
              {listing.productName ?? "Nguồn hàng cà phê"}
            </h2>
            <Button type="button" variant="ghost" onClick={onChangeListing}>
              Đổi nguồn hàng
            </Button>
          </div>
          <KeyValueList
            items={[
              {
                label: "Đơn giá chào bán",
                value: (
                  <MoneyDisplay amount={listing.askingUnitPrice} unit="₫/kg" />
                ),
              },
              { label: "Khả dụng", value: kg(listing.quantityAvailable) },
              { label: "Đặt tối thiểu", value: kg(listing.minOrderQuantity) },
              {
                label: "Vùng trồng",
                value: listing.regionText ?? "Chưa có thông tin",
              },
            ]}
          />
          {stale && (
            <Banner
              variant="warning"
              actionLabel="Tải lại nguồn hàng"
              onAction={() => {
                setStale(false)
                reloadListing()
              }}
            >
              Nguồn hàng vừa thay đổi hoặc không đủ số lượng. Tải lại rồi kiểm
              tra khối lượng các đợt trước khi gửi lại.
            </Banner>
          )}
          {operation.pending && !operation.busy && (
            <Banner variant="warning" role="status">
              Chưa xác nhận được kết quả của đề nghị trước.{" "}
              <Link to="/contracts" className="underline">Xem danh sách hợp đồng</Link>
              {" "}để kiểm tra trước khi gửi lại.
            </Banner>
          )}
        </section>

        <fieldset disabled={!allowed || busy} className="space-y-5">
          <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
            <h2 className="text-lg">Giá</h2>
            <div className="grid gap-3 sm:grid-cols-3">
              <TextInput
                id="offer-price"
                label="Đơn giá thoả thuận"
                unit="₫/kg"
                inputMode="decimal"
                required
                {...register("agreedPrice")}
                error={errors.agreedPrice?.message}
              />
              <TextInput
                id="offer-min"
                label="Giá sàn điều chỉnh"
                unit="₫/kg"
                inputMode="decimal"
                {...register("minimumUnitPrice")}
                error={errors.minimumUnitPrice?.message}
              />
              <TextInput
                id="offer-max"
                label="Giá trần điều chỉnh"
                unit="₫/kg"
                inputMode="decimal"
                {...register("maximumUnitPrice")}
                error={errors.maximumUnitPrice?.message}
              />
            </div>
            <p className="text-xs text-text-secondary">
              Giá từng đợt chỉ được điều chỉnh trong biên độ này, khi cả hai bên
              cùng đồng ý và trước khi khoá tiền đợt.
            </p>
          </section>

          <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
            <h2 className="text-lg">Lịch giao hàng</h2>
            {milestones.fields.map((field, index) => (
              <div
                key={field.id}
                className="grid gap-3 rounded-sm border border-border p-3 sm:grid-cols-[auto_1fr_1fr_120px_auto] sm:items-end"
              >
                <span className="text-sm font-medium sm:pb-3">
                  Đợt {index + 1}
                </span>
                <TextInput
                  id={`m-${index}-qty`}
                  label="Khối lượng"
                  unit="kg"
                  inputMode="decimal"
                  {...register(`milestones.${index}.committedQuantity`)}
                  error={errors.milestones?.[index]?.committedQuantity?.message}
                />
                <DatePicker
                  id={`m-${index}-date`}
                  label="Ngày giao dự kiến"
                  {...register(`milestones.${index}.expectedDeliveryDate`)}
                  error={
                    errors.milestones?.[index]?.expectedDeliveryDate?.message
                  }
                />
                <TextInput
                  id={`m-${index}-grace`}
                  label="Ân hạn"
                  unit="ngày"
                  inputMode="numeric"
                  {...register(`milestones.${index}.graceDays`)}
                  error={errors.milestones?.[index]?.graceDays?.message}
                />
                <Button
                  type="button"
                  variant="ghost"
                  disabled={milestones.fields.length === 1}
                  onClick={() => milestones.remove(index)}
                >
                  Xoá
                </Button>
              </div>
            ))}
            <Button
              type="button"
              variant="outline"
              onClick={() =>
                milestones.append({
                  committedQuantity: "",
                  expectedDeliveryDate: "",
                  graceDays: 2,
                })
              }
            >
              + Thêm đợt giao
            </Button>
          </section>

          <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
            <h2 className="text-lg">Chất lượng cam kết</h2>
            <div className="grid gap-3 sm:grid-cols-2">
              <Controller
                name="coffeeType"
                control={control}
                render={({ field }) => (
                  <SelectDropdown
                    id="offer-type"
                    label="Loại cà phê"
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    options={[
                      { value: "ROBUSTA", label: "Robusta" },
                      { value: "ARABICA", label: "Arabica" },
                    ]}
                  />
                )}
              />
              <TextInput
                id="offer-moisture"
                label="Độ ẩm tối đa"
                unit="%"
                inputMode="decimal"
                {...register("moisturePercent")}
                error={errors.moisturePercent?.message}
              />
              <TextInput
                id="offer-foreign"
                label="Tạp chất tối đa"
                unit="%"
                inputMode="decimal"
                {...register("foreignMatterPercent")}
                error={errors.foreignMatterPercent?.message}
              />
              <TextInput
                id="offer-broken"
                label="Hạt đen vỡ tối đa"
                unit="%"
                inputMode="decimal"
                {...register("blackBrokenBeansPercent")}
                error={errors.blackBrokenBeansPercent?.message}
              />
            </div>
          </section>

          <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
            <h2 className="text-lg">Ký quỹ, dung sai và chế tài</h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <TextInput
                id="offer-buyer-dep"
                label="Cọc bên mua"
                unit="%"
                inputMode="decimal"
                {...register("buyerDepositRate")}
                error={errors.buyerDepositRate?.message}
                hint="3–10%"
              />
              <TextInput
                id="offer-seller-dep"
                label="Cọc bên bán"
                unit="%"
                inputMode="decimal"
                {...register("sellerDepositRate")}
                error={errors.sellerDepositRate?.message}
              />
              <TextInput
                id="offer-tolerance"
                label="Dung sai hao hụt"
                unit="%"
                inputMode="decimal"
                {...register("toleranceRate")}
                error={errors.toleranceRate?.message}
                hint="0–10%"
              />
              <TextInput
                id="offer-shortfall"
                label="Ngưỡng phạt giao thiếu"
                unit="%"
                inputMode="decimal"
                {...register("shortfallPenaltyThreshold")}
                error={errors.shortfallPenaltyThreshold?.message}
                hint="3–15%"
              />
              <TextInput
                id="offer-buyer-pen"
                label="Phạt bên mua"
                unit="%"
                inputMode="decimal"
                {...register("buyerPenaltyRate")}
                error={errors.buyerPenaltyRate?.message}
                hint="Tối đa 8%"
              />
              <TextInput
                id="offer-seller-pen"
                label="Phạt bên bán"
                unit="%"
                inputMode="decimal"
                {...register("sellerPenaltyRate")}
                error={errors.sellerPenaltyRate?.message}
                hint="Tối đa 8%"
              />
              <TextInput
                id="offer-confirm"
                label="Hạn xác nhận nhận hàng"
                unit="ngày LV"
                inputMode="numeric"
                {...register("buyerConfirmWindowDays")}
                error={errors.buyerConfirmWindowDays?.message}
                hint="2–10 ngày làm việc"
              />
              <TextInput
                id="offer-lead"
                label="Khoá tiền đợt trước hạn giao"
                unit="ngày LV"
                inputMode="numeric"
                {...register("fundingLeadBusinessDays")}
                error={errors.fundingLeadBusinessDays?.message}
                hint="3–60 ngày làm việc"
              />
              <Controller
                name="inspectionRequirement"
                control={control}
                render={({ field }) => (
                  <SelectDropdown
                    id="offer-inspection"
                    label="Giám định"
                    value={field.value}
                    onChange={(v) => v && field.onChange(v)}
                    options={[
                      {
                        value: "NONE_UNLESS_DISPUTED",
                        label: "Chỉ khi có tranh chấp",
                      },
                      {
                        value: "MANDATORY_BEFORE_SETTLEMENT",
                        label: "Bắt buộc trước tất toán",
                      },
                    ]}
                  />
                )}
              />
            </div>
            <p className="text-xs text-text-secondary">
              Phân bổ giao nhận, chính sách sai lệch chất lượng và luật áp dụng
              theo mẫu VN_COFFEE_B2B; xem đầy đủ trong chi tiết hợp đồng trước
              khi ký.
            </p>
          </section>
        </fieldset>
        {error && (
          <Banner role="alert" variant="danger">
            {error}
          </Banner>
        )}
      </div>
      <StickyFormSummary
        title="Tóm tắt đề nghị"
        items={[
          { label: "Số đợt giao", value: milestones.fields.length },
          { label: "Tổng khối lượng", value: kg(total || null) },
          {
            label: "Khả dụng",
            value: kg(listing.quantityAvailable),
          },
        ]}
        footer={
          <div className="space-y-3">
            <p className="text-xs text-text-secondary">
              Tổng giá trị và tiền cọc do máy chủ tính khi tạo đề nghị. Gửi đề
              nghị sẽ giữ số lượng trên nguồn hàng cho tới khi ký hoặc rút.
            </p>
            <Button
              type="submit"
              className="w-full"
              disabled={!allowed || busy}
            >
              {busy ? "Đang gửi…" : "Gửi đề nghị"}
            </Button>
            <Link
              to="/contracts"
              className="block text-center text-sm text-primary"
            >
              Huỷ
            </Link>
          </div>
        }
      />
    </form>
  )
}
