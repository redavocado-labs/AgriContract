import { useState } from "react"
import type { ParticipantRole } from "@/features/contracts/types/contract"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { EvidenceUploader } from "@/shared/ui/evidence-uploader"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { Skeleton } from "@/shared/ui/skeleton"
import { Textarea } from "@/shared/ui/textarea"
import { useToast } from "@/shared/ui/toast"
import {
  addContestEvidence,
  contestBreachCase,
  type BreachCase,
  type MilestoneDetail,
} from "../api/milestones"
import {
  useEvidenceSlot,
  useMilestoneBreachCase,
  useMilestoneCommand,
} from "../hooks/use-milestone"
import { displayKg } from "../lib/milestone-consent"
import { breachStatusLabel } from "../lib/presentation"
import { contestAction } from "../lib/contest"
import { EvidenceGallery, Panel, ServerDeadline } from "./milestone-parts"
import { contractError } from "@/features/contracts/types/presentation"

const disposition = {
  CONFORMING: "Đạt chất lượng",
  PARTIALLY_CONFORMING: "Đạt một phần",
  NON_CONFORMING: "Không đạt",
}
const partyLabel = { BUYER: "Bên mua", SELLER: "Bên bán" }

export function BreachCasePanel({
  detail,
  role,
  canAct,
}: {
  detail: MilestoneDetail
  role: ParticipantRole
  canAct: boolean
}) {
  const query = useMilestoneBreachCase(detail)
  if (query.isPending) return <Skeleton className="h-36" />
  if (query.isError)
    return (
      <Panel title="Hồ sơ tranh chấp">
        <Banner variant="danger" role="alert">
          {contractError(query.error)}
        </Banner>
        <Button variant="outline" onClick={() => void query.refetch()}>
          Tải lại hồ sơ
        </Button>
      </Panel>
    )
  const breach = query.data
  return (
    <BreachDetail
      key={breach.breachCaseId}
      breach={breach}
      detail={detail}
      role={role}
      canAct={canAct}
    />
  )
}

function BreachDetail({
  breach,
  detail,
  role,
  canAct,
}: {
  breach: BreachCase
  detail: MilestoneDetail
  role: ParticipantRole
  canAct: boolean
}) {
  const [expiredDeadline, setExpiredDeadline] = useState<string | null>(null)
  const action = canAct
    ? contestAction(breach, detail.milestone.status, role)
    : null
  const report = breach.computedResolution
  return (
    <Panel
      title="Hồ sơ tranh chấp"
      description={breachStatusLabel[breach.status]}
    >
      {report && (
        <KeyValueList
          items={[
            {
              label: "Mã báo cáo giám định",
              value: (
                <span className="break-all font-mono">
                  {report.inspectionReportId}
                </span>
              ),
            },
            {
              label: "Khối lượng được chấp nhận",
              value: `${displayKg(report.acceptedQuantityKg)} kg`,
            },
            ...(report.qualityDisposition
              ? [
                  {
                    label: "Chất lượng",
                    value: disposition[report.qualityDisposition],
                  },
                ]
              : []),
            {
              label: "Tiền bên bán nhận theo báo cáo",
              value: <MoneyDisplay amount={report.sellerEntitlementAmount} />,
            },
            {
              label: "Tiền bên mua được hoàn theo báo cáo",
              value: <MoneyDisplay amount={report.buyerRefundAmount} />,
            },
          ]}
        />
      )}
      {breach.resolvedAt && (
        <KeyValueList
          items={[
            {
              label: "Thời điểm quyết định",
              value: vietnamTime(breach.resolvedAt),
            },
            {
              label: "Bên bị quy lỗi",
              value: breach.finalBreachingRole
                ? partyLabel[breach.finalBreachingRole]
                : "Không có bên bị quy lỗi",
            },
          ]}
        />
      )}
      {breach.contestStatus === "OPEN" && breach.contestDeadline && (
        <ServerDeadline
          key={breach.contestDeadline}
          label="Hạn phản đối"
          closesAt={breach.contestDeadline}
          onExpire={() => setExpiredDeadline(breach.contestDeadline)}
        />
      )}
      {breach.contestStatus === "CONTESTED" && (
        <Banner variant="info">
          {detail.milestone.status === "SETTLED"
            ? "Hồ sơ đã được xử lý sau phản đối."
            : breach.status === "UNDER_REVIEW"
              ? "Đã có kết quả giám định lần hai, đang chờ trọng tài duyệt."
              : "Đã ghi nhận phản đối. Kết quả giám định lần hai là kết quả cuối cùng."}
        </Banner>
      )}
      {action &&
        (action !== "contest" ||
          expiredDeadline !== breach.contestDeadline) && (
          <ContestForm
            key={action}
            action={action}
            detail={detail}
            breach={breach}
            role={role}
          />
        )}
    </Panel>
  )
}

function ContestForm({
  action,
  breach,
  detail,
  role,
}: {
  action: "contest" | "evidence"
  breach: BreachCase
  detail: MilestoneDetail
  role: ParticipantRole
}) {
  const command = useMilestoneCommand(detail)
  const slot = useEvidenceSlot(detail)
  const toast = useToast()
  const [reason, setReason] = useState("")
  const [files, setFiles] = useState<string[]>([])
  const selected = [
    ...new Set([...files, ...(slot.fileId ? [slot.fileId] : [])]),
  ]
  const contest = action === "contest"
  const ready =
    !slot.pending &&
    (!slot.item || !!slot.fileId) &&
    (contest ? !!reason.trim() : selected.length > 0)
  async function submit() {
    if (
      !ready ||
      contestAction(breach, detail.milestone.status, role) !== action
    )
      return
    const ok = await command.run(
      JSON.stringify([action, reason.trim(), selected]),
      (key) =>
        contest
          ? contestBreachCase(breach, reason.trim(), selected, key)
          : addContestEvidence(breach, selected, key)
    )
    if (!ok) return
    setFiles([])
    slot.clear()
    toast.add({
      title: contest ? "Đã gửi phản đối" : "Đã bổ sung bằng chứng",
      type: "success",
    })
  }
  return (
    <form
      className="space-y-3 border-t border-border pt-4"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      {contest && (
        <Textarea
          id="contest-reason"
          label="Lý do phản đối"
          required
          maxLength={1000}
          disabled={command.busy}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      )}
      <EvidenceGallery detail={detail} role={role} fileIds={files} />
      {files.length > 0 && (
        <Button
          type="button"
          variant="ghost"
          disabled={command.busy}
          onClick={() => setFiles([])}
        >
          Bỏ các tệp đã chọn
        </Button>
      )}
      {files.length < 10 && (
        <EvidenceUploader
          label={contest ? "Bằng chứng (không bắt buộc)" : "Bằng chứng bổ sung"}
          value={slot.item}
          onSelect={slot.select}
          disabled={command.busy || slot.pending}
        />
      )}
      {slot.item && !slot.pending && (
        <Button
          type="button"
          variant="outline"
          disabled={command.busy}
          onClick={() => {
            if (slot.fileId) setFiles(selected)
            slot.clear()
          }}
        >
          {slot.fileId ? "Giữ tệp và chọn thêm" : "Bỏ tệp này"}
        </Button>
      )}
      {command.error && (
        <Banner role="alert" variant="danger">
          {command.error}
        </Banner>
      )}
      <div>
        <Button className="w-full sm:w-auto" disabled={!ready || command.busy}>
          {contest ? "Gửi phản đối" : "Bổ sung bằng chứng"}
        </Button>
      </div>
    </form>
  )
}
