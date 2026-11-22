import type { ReactNode } from "react"
import { BlobImage } from "@/shared/ui/blob-image"
import { Checkbox } from "@/shared/ui/checkbox"
import { ContentHashDisplay } from "@/shared/ui/content-hash-display"
import { DeadlinePill } from "@/shared/ui/deadline-pill"
import { StatusBadge } from "@/shared/ui/status-badge"
import { Countdown } from "@/shared/ui/countdown"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import type { MilestoneStatus } from "@/features/contracts/types/contract"
import { readEvidence, type MilestoneDetail } from "../api/milestones"
import { MILESTONE_STATEMENT_VERSION } from "../lib/milestone-consent"
import {
  daysLeft,
  milestoneStatusLabel,
  milestoneStatusVariant,
  secondsLeft,
} from "../lib/presentation"

export function MilestoneBadge({ status }: { status: MilestoneStatus }) {
  return (
    <StatusBadge variant={milestoneStatusVariant[status]}>
      {milestoneStatusLabel[status]}
    </StatusBadge>
  )
}

/** A card section in the page's existing surface style. */
export function Panel({
  title,
  description,
  aside,
  children,
}: {
  title: string
  description?: ReactNode
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="space-y-4 rounded-md border border-border bg-surface p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <h2 className="text-lg">{title}</h2>
          {description && (
            <p className="text-sm text-text-secondary">{description}</p>
          )}
        </div>
        {aside}
      </div>
      {children}
    </section>
  )
}

/** Server deadline: "Còn N ngày" pill (or mm:ss under one hour) plus the exact time. */
export function ServerDeadline({
  label,
  closesAt,
  onExpire,
}: {
  label: string
  closesAt: string
  onExpire?: () => void
}) {
  const seconds = secondsLeft(closesAt)
  const days = daysLeft(closesAt)
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-sm border border-border bg-page-bg px-3 py-2.5">
      <span className="ms text-[18px] text-text-secondary">schedule</span>
      <span className="text-sm text-text-secondary">{label}</span>
      {seconds <= 0 ? (
        <DeadlinePill status="overdue" days={0} />
      ) : seconds < 3600 ? (
        <Countdown
          seconds={seconds}
          onExpire={onExpire}
          className="text-base text-warning"
        />
      ) : days < 1 ? (
        <StatusBadge variant="warning">
          Còn {Math.floor(seconds / 3600)} giờ
        </StatusBadge>
      ) : days < 2 ? (
        <DeadlinePill status="due-soon" days={days} />
      ) : (
        <StatusBadge variant="info">Còn {days} ngày</StatusBadge>
      )}
      <span className="text-xs text-text-muted sm:ml-auto">
        Hạn {vietnamTime(closesAt)}
      </span>
    </div>
  )
}

/** Checkbox + statement word for word + version + the hash it binds to. */
export function ConsentCheckbox({
  statement,
  hash,
  checked,
  onChange,
  disabled,
}: {
  statement: string
  hash: string | null
  checked: boolean
  onChange: (value: boolean) => void
  disabled?: boolean
}) {
  return (
    <div className="space-y-2 rounded-sm border border-border bg-page-bg p-3">
      <Checkbox
        checked={checked}
        disabled={disabled || !hash}
        onCheckedChange={onChange}
        className="mt-0.5 self-start"
        label={<span className="leading-6">{statement}</span>}
      />
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 pl-6 text-xs text-text-secondary">
        <span>Phiên bản cam kết {MILESTONE_STATEMENT_VERSION}</span>
        {hash ? (
          <span className="flex items-center gap-1">
            Mã nội dung <ContentHashDisplay hash={hash} className="text-xs" />
          </span>
        ) : (
          <span>Đang tính mã nội dung…</span>
        )}
      </div>
    </div>
  )
}

/** READY evidence of the milestone, read through the authorised proxy. */
export function EvidenceGallery({
  detail,
  role,
  fileIds,
  emptyText,
}: {
  detail: MilestoneDetail
  role: "BUYER" | "SELLER"
  fileIds?: (string | null | undefined)[]
  emptyText?: string
}) {
  const wanted = fileIds?.filter(Boolean)
  const files = detail.evidence.filter(
    (file) =>
      file.uploadedByRole === role &&
      file.status === "READY" &&
      (!wanted || wanted.includes(file.fileId))
  )
  if (!files.length)
    return (
      <p className="text-sm text-text-muted">
        {emptyText ?? "Chưa có ảnh bằng chứng."}
      </p>
    )
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {files.map((file) => (
        <figure key={file.fileId} className="space-y-1">
          <BlobImage
            cacheKey={file.fileId}
            alt={`Ảnh bằng chứng ${role === "SELLER" ? "bên bán" : "bên mua"}`}
            className="aspect-[4/3] w-full"
            load={(signal) =>
              readEvidence(
                detail.contractId,
                detail.milestone.milestoneId,
                file.fileId,
                signal
              )
            }
          />
          <figcaption className="text-xs text-text-muted">
            {vietnamTime(file.uploadedAt).replace(" (giờ Việt Nam)", "")}
          </figcaption>
        </figure>
      ))}
    </div>
  )
}
