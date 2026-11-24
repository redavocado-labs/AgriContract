import { useState } from "react"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { ContentHashDisplay } from "@/shared/ui/content-hash-display"
import { EmptyState } from "@/shared/ui/empty-state"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { Modal } from "@/shared/ui/modal"
import { Skeleton } from "@/shared/ui/skeleton"
import { StatusBadge } from "@/shared/ui/status-badge"
import { Tooltip } from "@/shared/ui/tooltip"
import { cn } from "@/shared/lib/utils"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import {
  auditError,
  type AdminAuditRecord,
  type AnchorStatus,
  type AuditChainVerification,
  type PartyAuditRecord,
} from "../api/audit"
import {
  usePartyContractAuditTrail,
  usePartyContractVerification,
} from "../hooks/use-audit"
import {
  brokenReasonMessage,
  formatAuditEventLabel,
  formatSubjectLabel,
  isUnreadableAuditRecord,
  newestFirst,
  truncateAuditHash,
} from "../lib/event-labels"

type AnyAuditRecord = PartyAuditRecord | AdminAuditRecord

function isAdminRecord(record: AnyAuditRecord): record is AdminAuditRecord {
  return "globalSeq" in record
}

function formatContentValue(value: unknown): string {
  if (value === null) return "null"
  if (value === undefined) return "—"
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean")
    return String(value)
  return JSON.stringify(value)
}

export function AuditChainSummary({
  currentRecordHash,
  anchorStatus,
  verification,
  verifying,
  verifyError,
  onVerify,
}: {
  currentRecordHash: string
  anchorStatus: AnchorStatus
  verification?: AuditChainVerification
  verifying: boolean
  verifyError?: unknown
  onVerify: () => void
}) {
  return (
    <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <p className="text-xs text-text-secondary">Mã bằng chứng hiện tại</p>
          <ContentHashDisplay hash={currentRecordHash} className="max-w-full break-all" />
        </div>
        <Button
          variant="outline"
          size="sm"
          disabled={verifying}
          onClick={onVerify}
        >
          {verifying ? "Đang kiểm tra…" : "Kiểm tra lại"}
        </Button>
      </div>

      <div className="grid gap-3 border-t-[0.5px] border-border pt-3 text-sm sm:grid-cols-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-text-secondary">Trạng thái chuỗi:</span>
          {verifying && !verification ? (
            <span className="text-text-secondary">Đang kiểm tra…</span>
          ) : verification?.status === "INTACT" ? (
            <span className="inline-flex flex-wrap items-center gap-1.5 font-medium text-success">
              <span aria-hidden="true">✔</span>
              <span>Nguyên vẹn</span>
              <span className="font-normal text-xs text-text-secondary">
                (kiểm lúc {vietnamTime(verification.checkedAt)})
              </span>
            </span>
          ) : verification?.status === "BROKEN" ? (
            <span className="inline-flex items-center gap-1.5 font-medium text-danger">
              <span aria-hidden="true">✖</span>
              <span>Chuỗi bị hỏng</span>
            </span>
          ) : verifyError ? (
            <span className="text-danger">{auditError(verifyError)}</span>
          ) : (
            <span className="text-text-secondary">Chưa kiểm tra</span>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-text-secondary">Đóng dấu thời gian:</span>
          {anchorStatus === "ANCHORED" ? (
            <StatusBadge variant="success">Đã đóng dấu OpenTimestamps</StatusBadge>
          ) : (
            <>
              <span className="text-text-primary">chưa có</span>
              <Tooltip content="Việc xoá các bản ghi mới nhất chỉ phát hiện được sau khi có dấu thời gian độc lập">
                <span
                  aria-label="Giải thích đóng dấu thời gian"
                  className="ms text-base text-text-muted"
                >
                  info
                </span>
              </Tooltip>
            </>
          )}
        </div>
      </div>

      {verification?.status === "BROKEN" && (
        <Banner role="alert" variant="danger">
          <div className="space-y-1">
            <p className="font-medium">
              {brokenReasonMessage(verification.brokenReason)}
            </p>
            {verification.brokenRecordId && (
              <p className="font-mono text-xs break-all">
                Bản ghi hỏng: {verification.brokenRecordId}
              </p>
            )}
          </div>
        </Banner>
      )}
    </section>
  )
}

export function AuditRecordDetailModal({
  record,
  adminMode,
  onClose,
}: {
  record: AnyAuditRecord | null
  adminMode: boolean
  onClose: () => void
}) {
  if (!record) return null
  const unreadable = isAdminRecord(record) && isUnreadableAuditRecord(record)
  const contentEntries = record.content ? Object.entries(record.content) : []

  const items = [
    {
      label: "Sự kiện",
      value: unreadable ? (
        <span className="font-medium text-danger">Bản ghi không đọc được</span>
      ) : (
        formatAuditEventLabel(record)
      ),
    },
    {
      label: "Loại nguồn",
      value: (
        <span className="font-mono text-xs break-all">
          {record.sourceType ?? "—"} / {record.sourceEventType ?? "—"}
        </span>
      ),
    },
    {
      label: "Thời điểm",
      value: record.createdAt ? vietnamTime(record.createdAt) : "Không đọc được",
    },
    {
      label: "Mã bản ghi (recordHash)",
      value: (
        <ContentHashDisplay
          hash={record.recordHash}
          truncate={false}
          className="text-xs break-all"
        />
      ),
    },
    ...(adminMode && isAdminRecord(record)
      ? [
          {
            label: "Thứ tự toàn hệ thống (globalSeq)",
            value: <span className="font-mono">#{record.globalSeq}</span>,
          },
          {
            label: "Chủ thể",
            value: (
              <span className="break-all">
                {formatSubjectLabel(record.subjectType, record.subjectId)}{" "}
                {record.subjectId && (
                  <span className="font-mono text-xs text-text-secondary">
                    ({record.subjectId})
                  </span>
                )}
              </span>
            ),
          },
          {
            label: "Mã sự kiện nguồn (sourceEventId)",
            value: (
              <span className="font-mono text-xs break-all">
                {record.sourceEventId ?? "—"}
              </span>
            ),
          },
          {
            label: "Nối cùng chủ thể (prevHashSubject)",
            value: record.prevHashSubject ? (
              <ContentHashDisplay
                hash={record.prevHashSubject}
                truncate={false}
                className="text-xs break-all"
              />
            ) : (
              "Bản ghi đầu tiên của chủ thể"
            ),
          },
          {
            label: "Nối toàn hệ thống (prevHashGlobal)",
            value: record.prevHashGlobal ? (
              <ContentHashDisplay
                hash={record.prevHashGlobal}
                truncate={false}
                className="text-xs break-all"
              />
            ) : (
              "Bản ghi đầu tiên của hệ thống"
            ),
          },
        ]
      : []),
  ]

  return (
    <Modal
      open={Boolean(record)}
      onOpenChange={(open) => !open && onClose()}
      size="lg"
      title="Chi tiết bản ghi bằng chứng"
    >
      <div className="max-h-[70vh] space-y-4 overflow-y-auto pr-1">
        <KeyValueList items={items} />
        <div className="space-y-2">
          <h3 className="text-sm font-medium text-text-primary">
            Nội dung lưu trong chuỗi
          </h3>
          {contentEntries.length ? (
            <KeyValueList
              items={contentEntries.map(([key, val]) => ({
                label: key,
                value: (
                  <span className="font-mono text-xs break-all">
                    {formatContentValue(val)}
                  </span>
                ),
              }))}
            />
          ) : (
            <p className="text-sm text-text-secondary">
              {unreadable
                ? "Không đọc được nội dung bản ghi."
                : "Không có dữ liệu chi tiết."}
            </p>
          )}
        </div>
      </div>
    </Modal>
  )
}

function HashCell({ hash }: { hash: string | null }) {
  if (!hash) return <span className="text-xs text-text-muted">—</span>
  return (
    <span
      className="font-mono text-xs text-text-primary"
      title={hash}
      onClick={(e) => e.stopPropagation()}
    >
      <ContentHashDisplay hash={hash} className="text-xs" />
    </span>
  )
}

export function AuditRecordList({
  records,
  mode,
  brokenRecordId,
  indexOffset,
  onSelectContract,
}: {
  records: readonly AnyAuditRecord[]
  mode: "party" | "admin-contract" | "admin-global"
  brokenRecordId?: string | null
  /** For contract trails (total down to 1); admin-global uses globalSeq directly. */
  indexOffset?: number
  onSelectContract?: (contractId: string) => void
}) {
  const [selected, setSelected] = useState<AnyAuditRecord | null>(null)
  const isAdmin = mode !== "party"
  const isGlobal = mode === "admin-global"
  const total = indexOffset ?? records.length

  if (!records.length) {
    return (
      <EmptyState
        icon="history"
        title="Chưa có bản ghi bằng chứng"
        description="Nhật ký sẽ xuất hiện khi phát sinh sự kiện cần lưu vết."
      />
    )
  }

  return (
    <>
      <div className="hidden overflow-x-auto rounded-md border-[0.5px] border-border bg-surface md:block">
        <table className="w-full border-collapse text-left text-sm">
          <thead>
            <tr className="bg-surface-muted text-[13px] font-medium text-text-secondary">
              <th className="px-4 py-2.5">{isGlobal ? "Seq" : "#"}</th>
              {mode === "admin-contract" && <th className="px-4 py-2.5">Seq</th>}
              <th className="px-4 py-2.5">Thời điểm</th>
              {isGlobal && <th className="px-4 py-2.5">Chủ thể</th>}
              <th className="px-4 py-2.5">Sự kiện</th>
              <th className="px-4 py-2.5">Mã</th>
              {isAdmin && <th className="px-4 py-2.5">Nối cùng chủ thể</th>}
              {isAdmin && <th className="px-4 py-2.5">Nối toàn hệ thống</th>}
            </tr>
          </thead>
          <tbody>
            {records.map((row, idx) => {
              const adminRow = isAdminRecord(row) ? row : null
              const unreadable = adminRow ? isUnreadableAuditRecord(adminRow) : false
              const broken = Boolean(brokenRecordId && row.recordId === brokenRecordId)
              const orderLabel = isGlobal ? adminRow?.globalSeq : total - idx
              return (
                <tr
                  key={row.recordId}
                  onClick={() => setSelected(row)}
                  className={cn(
                    "cursor-pointer border-t-[0.5px] border-border transition-colors hover:bg-surface-muted/60",
                    broken && "bg-danger-tint/70 text-danger",
                    unreadable && !broken && "bg-danger-tint/40 text-danger"
                  )}
                >
                  <td className="px-4 py-3 font-mono text-xs whitespace-nowrap">
                    {orderLabel}
                  </td>
                  {mode === "admin-contract" && (
                    <td className="px-4 py-3 font-mono text-xs whitespace-nowrap">
                      {adminRow?.globalSeq}
                    </td>
                  )}
                  <td className="px-4 py-3 text-xs whitespace-nowrap text-text-secondary">
                    {row.createdAt ? vietnamTime(row.createdAt) : "—"}
                  </td>
                  {isGlobal && adminRow && (
                    <td
                      className="px-4 py-3 text-xs whitespace-nowrap"
                      onClick={(e) => e.stopPropagation()}
                    >
                      {adminRow.subjectType === "CONTRACT" &&
                      adminRow.subjectId &&
                      onSelectContract ? (
                        <button
                          type="button"
                          className="font-medium text-primary underline hover:text-primary/80"
                          onClick={() => onSelectContract(adminRow.subjectId!)}
                        >
                          {formatSubjectLabel(adminRow.subjectType, adminRow.subjectId)}
                        </button>
                      ) : (
                        formatSubjectLabel(adminRow.subjectType, adminRow.subjectId)
                      )}
                    </td>
                  )}
                  <td className="px-4 py-3">
                    {unreadable ? (
                      <span className="font-medium text-danger">
                        Bản ghi không đọc được
                      </span>
                    ) : (
                      <span className={cn(broken && "font-medium text-danger")}>
                        {formatAuditEventLabel(row)}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    <HashCell hash={row.recordHash} />
                  </td>
                  {isAdmin && adminRow && (
                    <>
                      <td className="px-4 py-3 whitespace-nowrap">
                        <HashCell hash={adminRow.prevHashSubject} />
                      </td>
                      <td className="px-4 py-3 whitespace-nowrap">
                        <HashCell hash={adminRow.prevHashGlobal} />
                      </td>
                    </>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className="space-y-3 md:hidden">
        {records.map((row, idx) => {
          const adminRow = isAdminRecord(row) ? row : null
          const unreadable = adminRow ? isUnreadableAuditRecord(adminRow) : false
          const broken = Boolean(brokenRecordId && row.recordId === brokenRecordId)
          const orderLabel = isGlobal ? adminRow?.globalSeq : total - idx
          return (
            <button
              key={row.recordId}
              type="button"
              onClick={() => setSelected(row)}
              className={cn(
                "w-full space-y-2 rounded-md border border-border bg-surface p-4 text-left",
                broken && "border-danger bg-danger-tint/60",
                unreadable && !broken && "border-danger/60 bg-danger-tint/30"
              )}
            >
              <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-text-secondary">
                <span className="font-mono">
                  #{orderLabel}
                  {mode === "admin-contract" && adminRow
                    ? ` · global #${adminRow.globalSeq}`
                    : ""}
                </span>
                <span>{row.createdAt ? vietnamTime(row.createdAt) : "—"}</span>
              </div>
              {isGlobal && adminRow && (
                <div className="text-xs text-text-secondary">
                  {formatSubjectLabel(adminRow.subjectType, adminRow.subjectId)}
                </div>
              )}
              <div
                className={cn(
                  "text-sm font-medium",
                  (broken || unreadable) && "text-danger"
                )}
              >
                {unreadable
                  ? "Bản ghi không đọc được"
                  : formatAuditEventLabel(row)}
              </div>
              <div className="font-mono text-xs text-text-secondary break-all">
                Mã: {truncateAuditHash(row.recordHash)}
              </div>
              {isAdmin && adminRow && (
                <div className="space-y-0.5 font-mono text-[11px] text-text-muted break-all">
                  <div>
                    Nối chủ thể:{" "}
                    {adminRow.prevHashSubject
                      ? truncateAuditHash(adminRow.prevHashSubject)
                      : "—"}
                  </div>
                  <div>
                    Nối hệ thống:{" "}
                    {adminRow.prevHashGlobal
                      ? truncateAuditHash(adminRow.prevHashGlobal)
                      : "—"}
                  </div>
                </div>
              )}
            </button>
          )
        })}
      </div>

      <AuditRecordDetailModal
        record={selected}
        adminMode={isAdmin}
        onClose={() => setSelected(null)}
      />
    </>
  )
}

export function ContractPartyAuditTab({
  contractId,
  active,
}: {
  contractId: string
  active: boolean
}) {
  const trail = usePartyContractAuditTrail(contractId, active)
  const verification = usePartyContractVerification(contractId, active)

  if (trail.isPending) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    )
  }

  if (trail.isError) {
    return (
      <EmptyState
        icon="history"
        title={auditError(trail.error)}
        description="Bản ghi bằng chứng bắt đầu được tạo từ thời điểm hợp đồng được ký."
      />
    )
  }

  if (!trail.data) return null
  const ordered = newestFirst(trail.data.records)

  return (
    <div className="space-y-4">
      <AuditChainSummary
        currentRecordHash={trail.data.currentRecordHash}
        anchorStatus={verification.data?.anchorStatus ?? trail.data.anchorStatus}
        verification={verification.data}
        verifying={verification.isFetching}
        verifyError={verification.error}
        onVerify={() => void verification.refetch()}
      />
      <AuditRecordList
        records={ordered}
        mode="party"
        brokenRecordId={
          verification.data?.status === "BROKEN"
            ? verification.data.brokenRecordId
            : null
        }
        indexOffset={trail.data.records.length}
      />
    </div>
  )
}
