import { useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"
import { newIdempotencyKey } from "@/shared/api/client"
import { parseApiError } from "@/shared/api/errors"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { Modal } from "@/shared/ui/modal"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { Skeleton } from "@/shared/ui/skeleton"
import { StatusBadge } from "@/shared/ui/status-badge"
import { useToast } from "@/shared/ui/toast"
import { contractError } from "@/features/contracts/types/presentation"
import {
  approveComputedResolution,
  getMilestoneDetail,
  reviewBreachCase,
  type BreachCase,
} from "@/features/milestones/api/milestones"
import { MilestoneDeltas } from "@/features/milestones/components/delta-rows"
import { EvidenceGallery } from "@/features/milestones/components/milestone-parts"
import { displayKg } from "@/features/milestones/lib/milestone-consent"
import {
  breachStatusLabel,
  issueTypeLabel,
} from "@/features/milestones/lib/presentation"

const dispositionLabel = {
  CONFORMING: "Đạt chất lượng",
  PARTIALLY_CONFORMING: "Đạt một phần",
  NON_CONFORMING: "Không đạt",
} as const
const roleLabel = { BUYER: "Bên mua", SELLER: "Bên bán" } as const

/**
 * D81: the Arbitrator approves the result computed from the confirmed Level 1.5
 * report. No figure is editable; the request equals computedResolution.
 */
export function ResolutionReview({
  breachCase,
  onApproved,
}: {
  breachCase: BreachCase
  onApproved?: () => void
}) {
  const toast = useToast()
  const cache = useQueryClient()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const lock = useRef(false)
  const keys = useRef<{ review: string; resolve: string } | null>(null)
  const resolution = breachCase.computedResolution
  const milestone = useQuery({
    queryKey: [
      "milestone-arbitration",
      breachCase.contractId,
      breachCase.milestoneId,
    ],
    queryFn: ({ signal }) =>
      getMilestoneDetail(
        breachCase.contractId,
        breachCase.milestoneId!,
        signal
      ),
    enabled: !!breachCase.milestoneId,
    retry: false,
  })
  async function approve() {
    if (!resolution || lock.current || breachCase.status === "RESOLVED") return
    lock.current = true
    keys.current ??= {
      review: newIdempotencyKey(),
      resolve: newIdempotencyKey(),
    }
    setBusy(true)
    setError("")
    try {
      if (breachCase.status === "REPORTED")
        await reviewBreachCase(
          breachCase.contractId,
          breachCase.breachCaseId,
          keys.current.review
        )
      await approveComputedResolution(
        breachCase,
        resolution,
        keys.current.resolve
      )
      keys.current = null
      setOpen(false)
      toast.add({ title: "Đã duyệt kết quả giám định", type: "success" })
      onApproved?.()
    } catch (failure) {
      setError(contractError(failure))
      const { status } = parseApiError(failure)
      if (status >= 400 && status < 500) keys.current = null
    } finally {
      await Promise.all([
        cache.invalidateQueries({ queryKey: ["arbitration"] }),
        cache.invalidateQueries({ queryKey: ["breach-case", breachCase.contractId] }),
        cache.invalidateQueries({ queryKey: ["milestone"] }),
      ])
      lock.current = false
      setBusy(false)
    }
  }
  const d = milestone.data
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 className="text-lg">
            {resolution
              ? issueTypeLabel[resolution.issueType]
              : "Tranh chấp đợt giao"}
            {d && ` · Đợt ${d.milestone.milestoneIndex}`}
          </h2>
          <p className="text-sm text-text-secondary">
            Hợp đồng{" "}
            <span className="font-mono text-text-primary">
              {breachCase.contractId.slice(0, 8)}
            </span>
            {" · "}mở lúc {vietnamTime(breachCase.reportedAt)}
          </p>
        </div>
        <StatusBadge
          variant={breachCase.status === "RESOLVED" ? "success" : "warning"}
        >
          {breachStatusLabel[breachCase.status]}
        </StatusBadge>
      </div>

      {!resolution ? (
        <Banner variant="info">
          Chưa có báo cáo giám định được xác nhận. Kết quả sẽ hiện ở đây khi đơn
          vị giám định gửi báo cáo.
        </Banner>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="rounded-md border border-border bg-page-bg p-4">
              <p className="text-sm text-text-secondary">
                Giải ngân cho bên bán
              </p>
              <MoneyDisplay
                amount={resolution.sellerEntitlementAmount}
                sign={
                  Number(resolution.sellerEntitlementAmount) > 0
                    ? "positive"
                    : "neutral"
                }
                emphasis="large"
              />
            </div>
            <div className="rounded-md border border-border bg-page-bg p-4">
              <p className="text-sm text-text-secondary">Hoàn về bên mua</p>
              <MoneyDisplay
                amount={resolution.buyerRefundAmount}
                emphasis="large"
              />
            </div>
          </div>
          <KeyValueList
            items={[
              {
                label: "Khối lượng giám định chấp nhận",
                value: (
                  <span className="font-mono">
                    {displayKg(resolution.acceptedQuantityKg)} kg
                  </span>
                ),
              },
              ...(resolution.qualityDisposition
                ? [
                    {
                      label: "Kết luận chất lượng",
                      value: dispositionLabel[resolution.qualityDisposition],
                    },
                  ]
                : []),
              {
                label: "Bên được kết quả ủng hộ",
                value: roleLabel[resolution.prevailingRole],
              },
              {
                label: "Phí giám định",
                value: `${roleLabel[resolution.prevailingRole === "BUYER" ? "SELLER" : "BUYER"]} chịu`,
              },
              {
                label: "Lỗi vi phạm",
                value: resolution.finalBreachingRole
                  ? "Bên bán vi phạm"
                  : "Không bên nào vi phạm",
              },
            ]}
          />
          {resolution.finalBreachingRole && (
            <Banner variant="warning">
              Kết quả có lỗi của bên bán: sau khi duyệt, bên bán có 2 ngày làm
              việc để khiếu nại trước khi chế tài được áp dụng.
            </Banner>
          )}
        </>
      )}

      {milestone.isPending && breachCase.milestoneId ? (
        <Skeleton className="h-32" />
      ) : d ? (
        <div className="space-y-4">
          <MilestoneDeltas
            committed={d.milestone.committedQuantity}
            seller={d.sellerDeclaration?.sellerDeclaredWeight}
            buyer={d.buyerReceipt?.buyerReceivedWeight}
          />
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <h3 className="text-sm font-medium">Ảnh bên bán</h3>
              <EvidenceGallery detail={d} role="SELLER" />
            </div>
            <div className="space-y-2">
              <h3 className="text-sm font-medium">Ảnh bên mua</h3>
              <EvidenceGallery detail={d} role="BUYER" />
            </div>
          </div>
          <Link
            to={`/contracts/${encodeURIComponent(breachCase.contractId)}/milestones/${encodeURIComponent(d.milestone.milestoneId)}`}
            className="inline-block text-sm text-primary hover:underline"
          >
            Xem chi tiết đợt giao
          </Link>
        </div>
      ) : null}

      {resolution && breachCase.status !== "RESOLVED" && (
        <div className="space-y-3 border-t border-border pt-4">
          <p className="text-sm text-text-secondary">
            Số liệu do hệ thống tính từ báo cáo giám định và điều khoản đã ký.
            Bạn chỉ duyệt, không sửa được con số.
          </p>
          {error && !open && (
            <Banner role="alert" variant="danger">
              {error}
            </Banner>
          )}
          <Button className="w-full sm:w-auto" onClick={() => setOpen(true)}>
            <span className="ms text-[18px]">gavel</span> Duyệt kết quả
          </Button>
        </div>
      )}
      {resolution && (
        <Modal
          open={open}
          onOpenChange={(next) => !busy && setOpen(next)}
          title="Duyệt kết quả giám định?"
          footer={
            <>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => setOpen(false)}
              >
                Xem lại
              </Button>
              <Button disabled={busy} onClick={() => void approve()}>
                {busy ? "Đang duyệt…" : "Duyệt kết quả"}
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            <p>
              Bên bán nhận{" "}
              <MoneyDisplay
                amount={resolution.sellerEntitlementAmount}
                className="text-sm"
              />
              , bên mua được hoàn{" "}
              <MoneyDisplay
                amount={resolution.buyerRefundAmount}
                className="text-sm"
              />
              .
            </p>
            <p>Quyết định được ghi vào hồ sơ và gửi cho cả hai bên.</p>
            {error && (
              <Banner role="alert" variant="danger">
                {error}
              </Banner>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}
