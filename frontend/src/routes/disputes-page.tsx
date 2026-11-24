import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { useNavigate, useParams, useSearchParams } from "react-router-dom"
import { ContractsLayout } from "@/features/contracts/components/contracts-layout"
import { contractError } from "@/features/contracts/types/presentation"
import { ResolutionReview } from "@/features/arbitration/components/resolution-review"
import {
  getBreachCase,
  listArbitrationQueue,
  type BreachCase,
} from "@/features/milestones/api/milestones"
import {
  breachStatusLabel,
  issueTypeLabel,
} from "@/features/milestones/lib/presentation"
import { useAuthStore } from "@/shared/auth/auth-store"
import { parseApiError } from "@/shared/api/errors"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import { EmptyState } from "@/shared/ui/empty-state"
import { Pagination } from "@/shared/ui/pagination"
import { QueueListWithPanel } from "@/shared/ui/queue-list-with-panel"
import { RecordCard } from "@/shared/ui/record-card"
import { Skeleton } from "@/shared/ui/skeleton"
import { StatusBadge } from "@/shared/ui/status-badge"

const caseTitle = (item: BreachCase) =>
  item.computedResolution
    ? issueTypeLabel[item.computedResolution.issueType]
    : item.breachReasonCode === "SHORTFALL"
      ? issueTypeLabel.QUANTITY
      : issueTypeLabel.QUALITY
const caseSubtitle = (item: BreachCase) =>
  `HĐ ${item.contractId.slice(0, 8)} · ${vietnamTime(item.reportedAt).replace(" (giờ Việt Nam)", "")}`

function Failure({ error, retry }: { error: unknown; retry: () => void }) {
  if (parseApiError(error).status === 403)
    return (
      <EmptyState
        icon="lock"
        title="Bạn không có quyền xem hàng đợi tranh chấp"
        description="Chỉ tài khoản Trọng tài viên mới duyệt được kết quả giám định."
      />
    )
  return (
    <EmptyState
      variant="error"
      title="Chưa tải được tranh chấp"
      description={contractError(error)}
      actionLabel="Thử lại"
      onAction={retry}
    />
  )
}

export function DisputesPage() {
  const { breachCaseId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const { userId, roles } = useAuthStore()
  const canReview = roles.includes("ARBITRATOR")
  const queue = useQuery({
    queryKey: ["arbitration", "queue", userId, page],
    queryFn: ({ signal }) => listArbitrationQueue(page, signal),
    enabled: canReview && !!userId,
    retry: false,
  })
  const fromQueue = queue.data?.content.find(
    (item) => item.breachCaseId === breachCaseId
  )
  const contractId = params.get("contractId") ?? fromQueue?.contractId
  // A deep link (from a milestone page) or an already-approved case is read on its own.
  const single = useQuery({
    queryKey: ["breach-case", contractId, breachCaseId, userId],
    queryFn: ({ signal }) => getBreachCase(contractId!, breachCaseId!, signal),
    enabled: canReview && !!userId && !!breachCaseId && !!contractId && !fromQueue,
    retry: false,
  })
  const selected = fromQueue ?? single.data
  const open = (id: string, contract: string) =>
    navigate(
      `/disputes/${encodeURIComponent(id)}?contractId=${encodeURIComponent(contract)}`
    )
  const items = queue.data?.content ?? []
  const detail = selected ? (
    <ResolutionReview key={selected.breachCaseId} breachCase={selected} />
  ) : single.isPending && breachCaseId && contractId ? (
    <Skeleton className="h-96" />
  ) : single.isError ? (
    <Failure error={single.error} retry={() => void single.refetch()} />
  ) : (
    <EmptyState
      icon="gavel"
      title="Chọn một vụ để xem"
      description="Kết quả giám định và số tiền đã tính sẵn hiện ở đây."
    />
  )
  return (
    <ContractsLayout
      title="Tranh chấp chờ duyệt"
      crumbs={
        breachCaseId
          ? [
              { label: "Tranh chấp", to: "/disputes" },
              { label: "Hồ sơ vụ việc" },
            ]
          : [{ label: "Tranh chấp" }]
      }
    >
      <p className="text-sm text-text-secondary">
        Các vụ đã có báo cáo giám định Level 1.5 được xác nhận. Hệ thống đã tính
        kết quả; bạn kiểm tra bằng chứng và duyệt.
      </p>
      {queue.isPending ? (
        <div
          role="status"
          aria-label="Đang tải hàng đợi"
          className="grid gap-4 lg:grid-cols-[320px_1fr]"
        >
          <Skeleton className="h-64" />
          <Skeleton className="hidden h-96 lg:block" />
        </div>
      ) : queue.isError ? (
        <Failure error={queue.error} retry={() => void queue.refetch()} />
      ) : !items.length && !breachCaseId ? (
        <EmptyState
          icon="task_alt"
          title="Không có vụ nào chờ duyệt"
          description="Khi đơn vị giám định gửi báo cáo cho một đợt đang tranh chấp, vụ việc sẽ hiện ở đây."
        />
      ) : (
        <>
          <QueueListWithPanel
            className="hidden lg:grid"
            items={items.map((item) => ({
              key: item.breachCaseId,
              title: caseTitle(item),
              subtitle: caseSubtitle(item),
              badge: (
                <StatusBadge variant="warning" className="mt-1">
                  {breachStatusLabel[item.status]}
                </StatusBadge>
              ),
            }))}
            activeKey={selected?.breachCaseId ?? null}
            onSelect={(key) => {
              const item = items.find((entry) => entry.breachCaseId === key)
              if (item) open(item.breachCaseId, item.contractId)
            }}
            detail={detail}
          />
          <div className="space-y-3 lg:hidden">
            {breachCaseId ? (
              <div className="rounded-md border border-border bg-surface p-4">
                {detail}
              </div>
            ) : (
              items.map((item) => (
                <RecordCard
                  key={item.breachCaseId}
                  code={caseTitle(item)}
                  badge={
                    <StatusBadge variant="warning">
                      {breachStatusLabel[item.status]}
                    </StatusBadge>
                  }
                  subtitle={caseSubtitle(item)}
                  chevron
                  onClick={() => open(item.breachCaseId, item.contractId)}
                />
              ))
            )}
          </div>
          {queue.data.totalPages > 1 && !breachCaseId && (
            <Pagination
              page={page + 1}
              totalPages={queue.data.totalPages}
              onPageChange={(p) => setPage(p - 1)}
            />
          )}
        </>
      )}
    </ContractsLayout>
  )
}
