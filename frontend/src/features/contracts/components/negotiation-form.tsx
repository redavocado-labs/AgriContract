import { useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { useQueryClient } from "@tanstack/react-query"
import { parseApiError } from "@/shared/api/errors"
import { useRecoverableWrite } from "@/shared/api/operation-recovery"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Button } from "@/shared/ui/button"
import { TextInput } from "@/shared/ui/text-input"
import { Banner } from "@/shared/ui/banner"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { useToast } from "@/shared/ui/toast"
import { negotiationSchema } from "../schemas/negotiation"
import { getContract, patchTerms, termsInput } from "../api/contracts"
import {
  canNegotiate,
  contractError,
  decimalMoney,
} from "../types/presentation"
import { useOrganizationPermissions } from "@/features/organization"
import type { Contract } from "../types/contract"

export function NegotiationForm({ contract }: { contract: Contract }) {
  const { canCreateOrSign } = useOrganizationPermissions()
  const userId = useAuthStore((state) => state.userId)
  const cache = useQueryClient()
  const toast = useToast()
  const original = decimalMoney(contract.terms.agreedPrice)
  const [review, setReview] = useState<string | null>(null)
  const [error, setError] = useState("")
  const [frozen, setFrozen] = useState(false)
  const operation = useRecoverableWrite("update-terms", contract.id)
  const { busy } = operation
  const allowed = canNegotiate(contract, canCreateOrSign) && !frozen
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(negotiationSchema),
    defaultValues: { agreedPrice: original ?? "" },
  })
  async function save() {
    if (!review || !userId || !allowed) return
    setError("")
    const request = termsInput(contract.terms, review)
    const queryKey = ["contract", userId, contract.id]
    let updated
    try {
      updated = await operation.run({
        binding: JSON.stringify(request),
        reconcile: async () => {
          const current = await cache.fetchQuery({
            queryKey,
            queryFn: ({ signal }) => getContract(contract.id, userId, signal),
            retry: false,
            staleTime: 0,
          })
          if (!canNegotiate(current, canCreateOrSign)) return "changed"
          return current.hash === contract.hash ? "replay" : "unresolved"
        },
        send: (key) => patchTerms(contract.id, request, key, userId),
      })
    } catch (failure) {
      const { code, status } = parseApiError(failure)
      setError(contractError(failure))
      if (code === "CONTRACT_TERMS_FROZEN") setFrozen(true)
      if (status === 409)
        void cache.invalidateQueries({ queryKey }).catch(() => {})
      return
    }
    if (useAuthStore.getState().userId !== userId) return
    setReview(null)
    cache.setQueryData(queryKey, updated)
    void cache.invalidateQueries({ queryKey: ["contracts", userId] }).catch(() => {})
    toast.add({ title: "Đã lưu điều khoản mới", type: "success" })
  }
  return (
    <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
      <div className="space-y-1">
        <h2 className="text-lg">Đàm phán điều khoản</h2>
        <p className="text-sm text-text-secondary">
          Hai bên sửa được điều khoản tới khi có chữ ký đầu tiên. Mỗi lần lưu
          tạo mã vân tay mới; cam kết ký cũ phải xác nhận lại.
        </p>
      </div>
      {!canCreateOrSign && (
        <Banner variant="warning">
          Chỉ hồ sơ đã xác minh và đúng vai trò bên tham gia mới được sửa điều
          khoản.
        </Banner>
      )}
      {frozen && (
        <Banner variant="info">
          Điều khoản đã khóa vì đã có chữ ký đầu tiên. Hãy ký hoặc rút đề nghị.
        </Banner>
      )}
      {operation.pending && !busy && (
        <Banner variant="warning" role="status">
          Kết quả lần lưu trước chưa được xác nhận. Kiểm tra điều khoản hiện tại
          trước khi gửi lại đúng thay đổi đã duyệt.
        </Banner>
      )}
      <form
        onSubmit={handleSubmit((data) => {
          setError("")
          setReview(data.agreedPrice)
        })}
        className="flex flex-col gap-3 sm:flex-row sm:items-end"
      >
        <TextInput
          id="proposed-price"
          label="Đơn giá đề xuất (VND/kg)"
          inputMode="decimal"
          disabled={!allowed || busy}
          containerClassName="sm:w-72"
          {...register("agreedPrice")}
          error={errors.agreedPrice?.message}
        />
        <Button type="submit" variant="outline" disabled={!allowed || busy}>
          Xem lại thay đổi
        </Button>
      </form>
      {review && (
        <div
          className="space-y-3 rounded-md border border-border bg-page-bg p-4 text-sm"
          role="status"
        >
          <div className="grid gap-2 sm:grid-cols-2">
            <p>
              <span className="block text-xs text-text-secondary">
                Đơn giá hiện hành
              </span>
              {original ? (
                <MoneyDisplay amount={original} unit="₫/kg" />
              ) : (
                "Chưa có thông tin"
              )}
            </p>
            <p>
              <span className="block text-xs text-text-secondary">
                Đơn giá đề xuất
              </span>
              <MoneyDisplay amount={review} unit="₫/kg" />
            </p>
          </div>
          <p className="text-text-secondary">
            Các điều khoản khác giữ nguyên. Tổng giá trị hợp đồng do máy chủ
            tính lại sau khi lưu.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button disabled={!allowed || busy} onClick={() => void save()}>
              {busy ? "Đang lưu…" : "Lưu điều khoản"}
            </Button>
            <Button
              variant="ghost"
              disabled={busy}
              onClick={() => setReview(null)}
            >
              Huỷ
            </Button>
          </div>
        </div>
      )}
      {error && (
        <Banner role="alert" variant="danger">
          {error}
        </Banner>
      )}
    </section>
  )
}
