import { useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { useRecoverableWrite } from "@/shared/api/operation-recovery"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { Modal } from "@/shared/ui/modal"
import { useToast } from "@/shared/ui/toast"
import { useOrganizationPermissions } from "@/features/organization"
import { getContract, withdrawOffer } from "../api/contracts"
import { canWithdraw, contractError, PRE_SIGNATURE } from "../types/presentation"
import type { Contract } from "../types/contract"

/** WITHDRAW_OFFER: either party, before the second signature; no penalty. */
export function WithdrawOffer({ contract }: { contract: Contract }) {
  const { canCreateOrSign } = useOrganizationPermissions()
  const userId = useAuthStore((state) => state.userId)
  const cache = useQueryClient()
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [error, setError] = useState("")
  const operation = useRecoverableWrite(
    "withdraw-offer",
    contract.id,
    !PRE_SIGNATURE.includes(contract.status)
  )
  const { busy } = operation
  if (!canWithdraw(contract, canCreateOrSign)) return null
  async function confirm() {
    if (!userId) return
    setError("")
    const queryKey = ["contract", userId, contract.id]
    try {
      await operation.run({
        binding: null,
        reconcile: async () => {
          const current = await cache.fetchQuery({
            queryKey,
            queryFn: ({ signal }) => getContract(contract.id, userId, signal),
            retry: false,
            staleTime: 0,
          })
          return canWithdraw(current, canCreateOrSign) ? "replay" : "changed"
        },
        send: (key) => withdrawOffer(contract.id, key),
      })
      if (useAuthStore.getState().userId !== userId) return
      setOpen(false)
      toast.add({ title: "Đã rút đề nghị hợp đồng", type: "success" })
    } catch (failure) {
      setError(contractError(failure))
    } finally {
      void Promise.allSettled([
        cache.invalidateQueries({ queryKey }),
        cache.invalidateQueries({ queryKey: ["contracts", userId] }),
      ])
    }
  }
  return (
    <>
      <Button variant="destructive" onClick={() => setOpen(true)}>
        Rút đề nghị
      </Button>
      <Modal
        open={open}
        onOpenChange={(next) => !busy && setOpen(next)}
        title="Rút đề nghị hợp đồng?"
        footer={
          <>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => setOpen(false)}
            >
              Giữ đề nghị
            </Button>
            <Button
              variant="destructive-solid"
              disabled={busy}
              onClick={() => void confirm()}
            >
              {busy ? "Đang rút…" : "Rút đề nghị"}
            </Button>
          </>
        }
      >
        <div className="space-y-3 text-sm">
          <p>
            Hợp đồng chuyển sang <strong>Đã rút đề nghị</strong> và không thể
            tiếp tục đàm phán hay ký. Số lượng đã giữ trên nguồn hàng được trả
            lại cho bên bán.
          </p>
          <p className="text-text-secondary">
            Chỉ rút được trước khi đủ hai chữ ký. Không bên nào bị phạt hay trừ
            uy tín.
          </p>
          {operation.pending && !busy && (
            <Banner variant="warning" role="status">
              Kết quả lần rút trước chưa được xác nhận. Hệ thống sẽ tải lại trạng
              thái hợp đồng trước khi gửi lại thao tác này.
            </Banner>
          )}
          {error && (
            <Banner role="alert" variant="danger">
              {error}
            </Banner>
          )}
        </div>
      </Modal>
    </>
  )
}
