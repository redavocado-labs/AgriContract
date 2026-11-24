import { Banner } from "@/shared/ui/banner"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { Skeleton } from "@/shared/ui/skeleton"
import { VerticalStepper } from "@/shared/ui/vertical-stepper"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import {
  type MilestoneDetail,
} from "../api/milestones"
import { displayKg } from "../lib/milestone-consent"
import {
  fundingLabel,
  issueTypeLabel,
  settlementPathLabel,
} from "../lib/presentation"
import { useMilestoneBreachCase } from "../hooks/use-milestone"
import { MilestoneDeltas } from "./delta-rows"
import { EvidenceGallery, Panel, ServerDeadline } from "./milestone-parts"

/** Read-only state for the side that has nothing to do right now. */
export function WaitingPanel({
  title,
  text,
  detail,
  deadline,
}: {
  title: string
  text: string
  detail: MilestoneDetail
  deadline?: { label: string; closesAt: string }
}) {
  const d = detail.sellerDeclaration
  const r = detail.buyerReceipt
  return (
    <Panel title={title} description={text}>
      {deadline && (
        <ServerDeadline label={deadline.label} closesAt={deadline.closesAt} />
      )}
      <MilestoneDeltas
        committed={detail.milestone.committedQuantity}
        seller={d?.sellerDeclaredWeight}
        buyer={r?.buyerReceivedWeight}
      />
      {d && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">Ảnh cân bên bán</h3>
          <EvidenceGallery
            detail={detail}
            role="SELLER"
            fileIds={[d.sellerEvidenceFileId, d.deliveryCertificateFileId]}
          />
        </div>
      )}
      {r && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">Ảnh cân bên mua</h3>
          <EvidenceGallery
            detail={detail}
            role="BUYER"
            fileIds={[r.buyerEvidenceFileId, r.deliveryCertificateFileId]}
          />
        </div>
      )}
    </Panel>
  )
}


export function FundingPanel({
  detail,
}: {
  detail: MilestoneDetail
}) {
  const m = detail.milestone
  const failed = m.fundingStatus === "FUNDING_FAILED"
  return (
    <Panel
      title="Chờ khoá tiền đợt"
      description="Bên bán chưa phải giao hàng cho tới khi tiền của đợt này được khoá."
    >
      <KeyValueList
        items={[
          {
            label: "Trạng thái khoá tiền",
            value: fundingLabel[m.fundingStatus],
          },
          {
            label: "Ngày yêu cầu khoá",
            value: vietnamTime(m.fundingScheduledDate),
          },
          {
            label: "Số tiền đợt",
            value: m.batchAmount ? (
              <MoneyDisplay amount={m.batchAmount} className="text-sm" />
            ) : (
              "Chốt khi yêu cầu khoá"
            ),
          },
        ]}
      />
      {failed && (
        <Banner variant="danger">
          Khoá tiền đợt chưa thành công sau các lần thử tự động. Hạn giao của
          bên bán tạm dừng cho tới khi khoá được.
        </Banner>
      )}
    </Panel>
  )
}

const steps = [
  "Bên bán phản đối",
  "Đơn vị giám định kiểm tra",
  "Trọng tài duyệt kết quả",
  "Tất toán đợt",
]

export function InspectionPendingPanel({
  detail,
}: {
  detail: MilestoneDetail
}) {
  const flag = detail.flag
  const breach = useMilestoneBreachCase(detail)
  const reported = !!breach.data?.computedResolution
  return (
    <Panel
      title="Đang giám định độc lập"
      description={
        flag
          ? `Báo ${issueTypeLabel[flag.issueType].toLowerCase()}, ${flag.contestedBy === "SYSTEM" ? "tự chuyển giám định vì bên bán không phản hồi" : "bên bán đã phản đối"} lúc ${vietnamTime(flag.contestedAt ?? flag.flaggedAt)}.`
          : undefined
      }
    >
      <Banner variant="info">
        Tiền đợt vẫn được giữ. Kết quả giám định Level 1.5 là căn cứ tất toán;
        trọng tài chỉ duyệt kết quả hệ thống tính từ báo cáo, không tự đổi số.
      </Banner>
      {breach.isPending && detail.breachCaseId ? (
        <Skeleton className="h-40" />
      ) : (
        <VerticalStepper
          steps={steps}
          currentStep={reported ? 3 : 2}
          currentBadgeLabel="Đang xử lý"
        />
      )}
      <MilestoneDeltas
        committed={detail.milestone.committedQuantity}
        seller={detail.sellerDeclaration?.sellerDeclaredWeight}
        buyer={detail.buyerReceipt?.buyerReceivedWeight}
      />
    </Panel>
  )
}

export function SettlementResult({ detail }: { detail: MilestoneDetail }) {
  const s = detail.settlement
  if (!s)
    return (
      <Panel
        title="Đã tất toán"
        description="Hệ thống chưa trả về chi tiết tất toán. Tải lại sau ít phút."
      >
        <span />
      </Panel>
    )
  const d = detail.sellerDeclaration
  const r = detail.buyerReceipt
  return (
    <Panel
      title="Kết quả tất toán"
      description={`${settlementPathLabel[s.path]} · ${vietnamTime(s.settledAt)}`}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-md border border-border bg-page-bg p-4">
          <p className="text-sm text-text-secondary">Giải ngân cho bên bán</p>
          <MoneyDisplay
            amount={s.sellerAmount}
            sign={Number(s.sellerAmount) > 0 ? "positive" : "neutral"}
            emphasis="large"
          />
        </div>
        <div className="rounded-md border border-border bg-page-bg p-4">
          <p className="text-sm text-text-secondary">Hoàn về bên mua</p>
          <MoneyDisplay amount={s.buyerRefundAmount} emphasis="large" />
        </div>
      </div>
      <KeyValueList
        items={[
          ...(d
            ? [
                {
                  label: "Bên bán khai",
                  value: `${displayKg(d.sellerDeclaredWeight)} kg`,
                },
              ]
            : []),
          ...(r
            ? [
                {
                  label: "Bên mua cân",
                  value: `${displayKg(r.buyerReceivedWeight)} kg`,
                },
              ]
            : []),
          {
            label: "Khối lượng tính tiền",
            value:
              s.billableQuantityKg == null
                ? "Tính theo số tiền"
                : `${displayKg(s.billableQuantityKg, 4)} kg`,
          },
          {
            label: "Đơn giá",
            value: (
              <MoneyDisplay
                amount={detail.milestone.effectiveUnitPrice}
                unit="₫/kg"
                className="text-sm"
              />
            ),
          },
          ...(detail.milestone.batchAmount
            ? [
                {
                  label: "Tiền đã khoá của đợt",
                  value: (
                    <MoneyDisplay
                      amount={detail.milestone.batchAmount}
                      className="text-sm"
                    />
                  ),
                },
              ]
            : []),
        ]}
      />
    </Panel>
  )
}
