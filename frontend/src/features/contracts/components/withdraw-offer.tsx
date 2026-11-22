import { useRef, useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { newIdempotencyKey } from "@/shared/api/client"
import { parseApiError } from "@/shared/api/errors"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { Modal } from "@/shared/ui/modal"
import { useToast } from "@/shared/ui/toast"
import { useOrganizationPermissions } from "@/features/organization/hooks/use-organization-permissions"
import { withdrawOffer } from "../api/contracts"
import { canWithdraw, contractError } from "../types/presentation"
import type { Contract } from "../types/contract"

/** WITHDRAW_OFFER: either party, before the second signature; no penalty. */
export function WithdrawOffer({ contract }: { contract: Contract }) {
  const { canCreateOrSign } = useOrganizationPermissions()
  const userId = useAuthStore((state) => state.userId)
  const cache = useQueryClient()
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const key = useRef<string | null>(null)
  if (!canWithdraw(contract, canCreateOrSign)) return null
  async function confirm() {
    if (busy) return
    key.current ??= newIdempotencyKey()
    setBusy(true)
    setError("")
    try {
      await withdrawOffer(contract.id, key.current)
      key.current = null
      setOpen(false)
      toast.add({ title: "Đã rút đề nghị hợp đồng", type: "success" })
    } catch (failure) {
      setError(contractError(failure))
      const { status } = parseApiError(failure)
      if (status >= 400 && status < 500) key.current = null
    } finally {
      setBusy(false)
      await cache.invalidateQueries({
        queryKey: ["contract", userId, contract.id],
      })
      await cache.invalidateQueries({ queryKey: ["contracts", userId] })
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
