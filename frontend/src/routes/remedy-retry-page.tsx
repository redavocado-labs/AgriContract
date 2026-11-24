import { useRef, useState } from "react"
import { useSearchParams } from "react-router-dom"
import { z } from "zod"
import { ContractsLayout } from "@/features/contracts/components/contracts-layout"
import { contractError } from "@/features/contracts/types/presentation"
import { retryRemedyLegs } from "@/features/milestones/api/milestones"
import { newIdempotencyKey } from "@/shared/api/client"
import { parseApiError } from "@/shared/api/errors"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { TextInput } from "@/shared/ui/text-input"

export function RemedyRetryPage() {
  const [params] = useSearchParams()
  const initialId = params.get("contractId") ?? ""
  return <RetryForm key={initialId} initialId={initialId} />
}

function RetryForm({ initialId }: { initialId: string }) {
  const [id, setId] = useState(initialId)
  const [status, setStatus] = useState<"idle" | "sending" | "accepted">("idle")
  const [error, setError] = useState("")
  const submitting = useRef(false)
  const operation = useRef<{ id: string; key: string } | undefined>(undefined)
  const contractId = id.trim().toLowerCase()
  const valid = z.uuid().safeParse(contractId).success

  async function submit() {
    if (!valid || submitting.current || status === "accepted") return
    submitting.current = true
    if (operation.current?.id !== contractId)
      operation.current = { id: contractId, key: newIdempotencyKey() }
    setStatus("sending")
    setError("")
    try {
      await retryRemedyLegs(contractId, operation.current.key)
      operation.current = undefined
      setStatus("accepted")
    } catch (failure) {
      const { status } = parseApiError(failure)
      if (status >= 400 && status < 500) operation.current = undefined
      setError(contractError(failure))
      setStatus("idle")
    } finally {
      submitting.current = false
    }
  }

  return (
    <ContractsLayout
      title="Gửi lại lệnh tiền"
      crumbs={[{ label: "Vận hành" }, { label: "Gửi lại lệnh tiền" }]}
    >
      <form
        className="max-w-xl space-y-4 rounded-md border border-border bg-surface p-4"
        onSubmit={(event) => {
          event.preventDefault()
          void submit()
        }}
      >
        <p className="text-sm text-text-secondary">
          Nhập mã hợp đồng từ thông báo lỗi. Hệ thống chỉ gửi lại các lệnh tiền
          đã thất bại của hợp đồng đang có hiệu lực.
        </p>
        <TextInput
          id="remedy-contract-id"
          className="font-mono text-[11px] min-[360px]:text-sm"
          label="Mã hợp đồng"
          value={id}
          required
          disabled={status === "sending"}
          onChange={(event) => {
            setId(event.target.value)
            setStatus("idle")
            setError("")
          }}
          error={id && !valid ? "Nhập mã hợp đồng đầy đủ." : undefined}
        />
        {error && (
          <Banner role="alert" variant="danger">
            {error}
          </Banner>
        )}
        {status === "accepted" && (
          <Banner role="status" variant="success">
            Đã gửi yêu cầu. Hệ thống sẽ xử lý các lệnh tiền bị lỗi.
          </Banner>
        )}
        <Button
          type="submit"
          className="w-full sm:w-auto"
          disabled={!valid || status !== "idle"}
        >
          {status === "sending" ? "Đang gửi…" : "Gửi lại lệnh tiền lỗi"}
        </Button>
      </form>
    </ContractsLayout>
  )
}
