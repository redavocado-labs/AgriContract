import { useState } from "react"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { EmptyState } from "@/shared/ui/empty-state"
import { Pagination } from "@/shared/ui/pagination"
import { SelectDropdown } from "@/shared/ui/select-dropdown"
import { Skeleton } from "@/shared/ui/skeleton"
import { TextInput } from "@/shared/ui/text-input"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import { auditError, type AuditSubjectType } from "../api/audit"
import {
  useAdminContractAuditTrail,
  useAdminContractVerification,
  useAdminGlobalAuditRecords,
  useVerifyGlobalAuditChain,
} from "../hooks/use-audit"
import { brokenReasonMessage, newestFirst } from "../lib/event-labels"
import { AuditChainSummary, AuditRecordList } from "./audit-trail-view"

const SUBJECT_OPTIONS: { value: AuditSubjectType | "ALL"; label: string }[] = [
  { value: "ALL", label: "Tất cả chủ thể" },
  { value: "CONTRACT", label: "Hợp đồng" },
  { value: "USER_PAIR", label: "Cặp người dùng" },
  { value: "SYSTEM", label: "Hệ thống" },
]

export function AdminContractAuditPanel({
  contractId,
  onChangeContractId,
}: {
  contractId: string
  onChangeContractId: (id: string) => void
}) {
  const [draft, setDraft] = useState(contractId)
  const activeId = contractId.trim()
  const trail = useAdminContractAuditTrail(activeId)
  const verification = useAdminContractVerification(activeId, Boolean(trail.data))

  const effectiveDraft =
    contractId && contractId !== draft && activeId === contractId
      ? contractId
      : draft

  function submit(e: React.FormEvent) {
    e.preventDefault()
    onChangeContractId(effectiveDraft.trim())
  }

  return (
    <div className="space-y-4">
      <form
        onSubmit={submit}
        className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:flex-row sm:items-end"
      >
        <div className="min-w-0 flex-1">
          <TextInput
            id="admin-audit-contract-id"
            label="Mã hợp đồng (UUID)"
            placeholder="Nhập mã hợp đồng để tra cứu chuỗi bằng chứng"
            value={effectiveDraft}
            onChange={(e) => setDraft(e.target.value)}
          />
        </div>
        <Button type="submit" disabled={!effectiveDraft.trim()}>
          Tra cứu
        </Button>
      </form>

      {!activeId ? (
        <EmptyState
          icon="search"
          title="Chưa chọn hợp đồng"
          description="Nhập mã hợp đồng hoặc bấm vào một hợp đồng từ tab Toàn hệ thống."
        />
      ) : trail.isPending ? (
        <div className="space-y-3">
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : trail.isError ? (
        <EmptyState
          icon="history"
          title={auditError(trail.error)}
          description="Kiểm tra lại mã hợp đồng hoặc thử tải lại sau."
          actionLabel="Thử lại"
          onAction={() => void trail.refetch()}
        />
      ) : (
        trail.data && (
          <>
            <AuditChainSummary
              currentRecordHash={trail.data.currentRecordHash}
              anchorStatus={
                verification.data?.anchorStatus ?? trail.data.anchorStatus
              }
              verification={verification.data}
              verifying={verification.isFetching}
              verifyError={verification.error}
              onVerify={() => void verification.refetch()}
            />
            <AuditRecordList
              records={newestFirst(trail.data.records)}
              mode="admin-contract"
              brokenRecordId={
                verification.data?.status === "BROKEN"
                  ? verification.data.brokenRecordId
                  : null
              }
              indexOffset={trail.data.records.length}
            />
          </>
        )
      )}
    </div>
  )
}

export function AdminGlobalAuditPanel({
  onOpenContract,
}: {
  onOpenContract: (contractId: string) => void
}) {
  const [subjectType, setSubjectType] = useState<AuditSubjectType | "ALL">("ALL")
  const [page, setPage] = useState(0)
  const list = useAdminGlobalAuditRecords(
    subjectType === "ALL" ? "" : subjectType,
    page,
    20
  )
  const verify = useVerifyGlobalAuditChain()

  function changeFilter(next: string | null) {
    setSubjectType((next ?? "ALL") as AuditSubjectType | "ALL")
    setPage(0)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3 rounded-md border border-border bg-surface p-4">
        <div className="w-full sm:w-56">
          <SelectDropdown
            id="admin-audit-subject-filter"
            label="Lọc theo chủ thể"
            value={subjectType}
            onChange={changeFilter}
            options={SUBJECT_OPTIONS}
          />
        </div>
        <Button
          variant="outline"
          disabled={verify.isPending}
          onClick={() => verify.mutate()}
        >
          {verify.isPending ? "Đang kiểm tra toàn chuỗi…" : "Kiểm tra toàn chuỗi"}
        </Button>
      </div>

      {verify.isError && (
        <Banner role="alert" variant="danger">
          {auditError(verify.error)}
        </Banner>
      )}

      {verify.data && (
        <Banner
          role={verify.data.status === "BROKEN" ? "alert" : "status"}
          variant={verify.data.status === "INTACT" ? "success" : "danger"}
        >
          {verify.data.status === "INTACT" ? (
            <span>
              ✔ Toàn chuỗi nguyên vẹn ({verify.data.checkedRecords} bản ghi ·
              kiểm lúc {vietnamTime(verify.data.checkedAt)})
            </span>
          ) : (
            <div className="space-y-1">
              <p className="font-medium">
                ✖ Toàn chuỗi bị hỏng — {brokenReasonMessage(verify.data.brokenReason)}
              </p>
              {verify.data.brokenRecordId && (
                <p className="font-mono text-xs break-all">
                  Bản ghi hỏng: {verify.data.brokenRecordId}
                </p>
              )}
            </div>
          )}
        </Banner>
      )}

      {list.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : list.isError ? (
        <EmptyState
          icon="error"
          title={auditError(list.error)}
          description="Không thể tải danh sách bản ghi toàn hệ thống."
          actionLabel="Thử lại"
          onAction={() => void list.refetch()}
        />
      ) : (
        list.data && (
          <>
            <AuditRecordList
              records={list.data.content}
              mode="admin-global"
              brokenRecordId={
                verify.data?.status === "BROKEN"
                  ? verify.data.brokenRecordId
                  : null
              }
              onSelectContract={onOpenContract}
            />
            {list.data.totalPages > 0 && (
              <Pagination
                page={list.data.page + 1}
                totalPages={list.data.totalPages}
                onPageChange={(next) => setPage(next - 1)}
              />
            )}
          </>
        )
      )}
    </div>
  )
}
