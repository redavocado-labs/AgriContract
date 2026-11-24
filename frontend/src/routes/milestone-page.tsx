import { Link, useNavigate, useParams } from "react-router-dom"
import { ContractsLayout } from "@/features/contracts/components/contracts-layout"
import { useContract } from "@/features/contracts/hooks/use-contracts"
import {
  contractError,
  participantRole,
} from "@/features/contracts/types/presentation"
import { useOrganizationPermissions } from "@/features/organization/hooks/use-organization-permissions"
import { RemedyDecisionDetails } from "@/features/contracts/components/remedy-decision"
import { BreachCasePanel } from "@/features/milestones/components/breach-case-panel"
import { DisputeExitPanel } from "@/features/milestones/components/dispute-exit-panel"
import type { MilestoneDetail } from "@/features/milestones/api/milestones"
import { useMilestoneDetail } from "@/features/milestones/hooks/use-milestone"
import {
  fundingLabel,
  milestoneTimeline,
} from "@/features/milestones/lib/presentation"
import { displayKg } from "@/features/milestones/lib/milestone-consent"
import {
  MilestoneBadge,
  Panel,
} from "@/features/milestones/components/milestone-parts"
import {
  BuyerDecisionPanel,
  BuyerReceiptPanel,
  SellerCorrectionPanel,
  SellerRespondPanel,
  SellerWeighPanel,
} from "@/features/milestones/components/party-actions"
import {
  FundingPanel,
  InspectionPendingPanel,
  SettlementResult,
  WaitingPanel,
} from "@/features/milestones/components/status-panels"
import { parseApiError } from "@/shared/api/errors"
import { useAuthStore } from "@/shared/auth/auth-store"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { EmptyState } from "@/shared/ui/empty-state"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MilestoneTimeline } from "@/shared/ui/milestone-timeline"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { Skeleton } from "@/shared/ui/skeleton"

type Viewer = "BUYER" | "SELLER" | "ARBITRATOR" | "OPERATOR" | null

export function MilestonePage() {
  const { id = "", milestoneId = "" } = useParams()
  return (
    <Milestone
      key={`${id}/${milestoneId}`}
      contractId={id}
      milestoneId={milestoneId}
    />
  )
}

function Milestone({
  contractId,
  milestoneId,
}: {
  contractId: string
  milestoneId: string
}) {
  const { roles, userId } = useAuthStore()
  const party = roles.includes("BUYER") || roles.includes("SELLER")
  const contract = useContract(party ? contractId : "")
  const detail = useMilestoneDetail(contractId, milestoneId)
  const viewer: Viewer =
    party && contract.data
      ? participantRole(contract.data, userId ?? "", roles)
      : roles.includes("ARBITRATOR")
        ? "ARBITRATOR"
        : roles.includes("OPERATOR")
          ? "OPERATOR"
          : null
  const index = detail.data?.milestone.milestoneIndex
  const crumbs = [
    ...(party
      ? [{ label: "Hợp đồng", to: "/contracts" }]
      : roles.includes("ARBITRATOR")
        ? [{ label: "Tranh chấp", to: "/disputes" }]
        : []),
    ...(party
      ? [
          {
            label: `HĐ ${contractId.slice(0, 8)}`,
            to: `/contracts/${encodeURIComponent(contractId)}`,
            mono: true,
          },
        ]
      : [{ label: `HĐ ${contractId.slice(0, 8)}`, mono: true }]),
    { label: index ? `Đợt ${index}` : "Đợt giao" },
  ]
  const loading = detail.isPending || (party && contract.isPending)
  const failure = detail.error ?? (party ? contract.error : null)
  return (
    <ContractsLayout
      title={index ? `Đợt giao ${index}` : "Đợt giao"}
      crumbs={crumbs}
      aside={
        detail.data && <MilestoneBadge status={detail.data.milestone.status} />
      }
    >
      {loading ? (
        <div
          role="status"
          aria-label="Đang tải đợt giao"
          className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_340px]"
        >
          <Skeleton className="h-96" />
          <div className="space-y-4">
            <Skeleton className="h-48" />
            <Skeleton className="h-64" />
          </div>
        </div>
      ) : failure ? (
        <MilestoneFailure
          error={failure}
          retry={() =>
            void Promise.all([detail.refetch(), party && contract.refetch()])
          }
        />
      ) : (
        detail.data && (
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
            <div className="min-w-0 space-y-5">
              <div className="flex justify-end">
                <Button
                  variant="outline"
                  disabled={detail.isFetching}
                  onClick={() => void detail.refetch()}
                >
                  Tải lại trạng thái
                </Button>
              </div>
              <Action
                detail={detail.data}
                viewer={viewer}
                hash={contract.data?.hash ?? null}
                inspectionPending={
                  contract.data?.terms.inspectionTerms != null &&
                  typeof contract.data.terms.inspectionTerms === "object" &&
                  "requirement" in contract.data.terms.inspectionTerms &&
                  contract.data.terms.inspectionTerms.requirement ===
                    "MANDATORY_BEFORE_SETTLEMENT"
                }
              />
            </div>
            <aside className="min-w-0 space-y-5">
              <Summary detail={detail.data} />
              <Panel title="Tiến trình đợt giao">
                <MilestoneTimeline nodes={milestoneTimeline(detail.data)} />
              </Panel>
            </aside>
          </div>
        )
      )}
    </ContractsLayout>
  )
}

function MilestoneFailure({
  error,
  retry,
}: {
  error: unknown
  retry: () => void
}) {
  const { status } = parseApiError(error)
  if (status === 403)
    return (
      <EmptyState
        icon="lock"
        title="Bạn không có quyền xem đợt giao này"
        description="Chỉ hai bên của hợp đồng và bộ phận vận hành, trọng tài mới xem được đợt giao."
      />
    )
  if (status === 404)
    return (
      <EmptyState
        icon="search_off"
        title="Không tìm thấy đợt giao"
        description="Đợt giao không thuộc hợp đồng này hoặc đường dẫn đã cũ."
      />
    )
  return (
    <EmptyState
      variant="error"
      title="Chưa tải được đợt giao"
      description={contractError(error)}
      actionLabel="Thử lại"
      onAction={retry}
    />
  )
}

function Summary({ detail }: { detail: MilestoneDetail }) {
  const m = detail.milestone
  return (
    <Panel title="Thông tin đợt">
      <KeyValueList
        className="px-4 py-2"
        items={[
          {
            label: "Khối lượng cam kết",
            value: (
              <span className="font-mono">
                {displayKg(m.committedQuantity)} kg
              </span>
            ),
          },
          {
            label: "Đơn giá",
            value: (
              <MoneyDisplay
                amount={m.effectiveUnitPrice}
                unit="₫/kg"
                className="text-sm"
              />
            ),
          },
          {
            label: "Tiền đợt",
            value: m.batchAmount ? (
              <MoneyDisplay amount={m.batchAmount} className="text-sm" />
            ) : (
              "Chưa chốt"
            ),
          },
          { label: "Khoá tiền", value: fundingLabel[m.fundingStatus] },
          {
            label: "Ngày giao dự kiến",
            value: vietnamTime(m.expectedDeliveryDate),
          },
          {
            label: "Hạn giao cuối",
            value: vietnamTime(m.effectiveDeliveryDeadline),
          },
        ]}
      />
    </Panel>
  )
}

function Action({
  detail,
  viewer,
  hash,
  inspectionPending,
}: {
  detail: MilestoneDetail
  viewer: Viewer
  hash: string | null
  inspectionPending: boolean
}) {
  const { canCreateOrSign } = useOrganizationPermissions()
  const navigate = useNavigate()
  const party = viewer === "BUYER" || viewer === "SELLER"
  const acting = party && canCreateOrSign
  const m = detail.milestone
  const notice =
    party && !canCreateOrSign ? (
      <Banner variant="warning">
        Hồ sơ doanh nghiệp chưa được xác minh nên bạn chỉ xem được đợt giao,
        chưa thao tác được.
      </Banner>
    ) : null
  const body = (() => {
    switch (m.status) {
      case "CREATED":
        return <FundingPanel detail={detail} />
      case "IN_PROGRESS":
        return acting && viewer === "SELLER" ? (
          <SellerWeighPanel detail={detail} />
        ) : (
          <WaitingPanel
            detail={detail}
            title="Bên bán đang chuẩn bị hàng"
            text={`Tiền đợt đã khoá. Bên bán cân và giao hàng trước hạn ${vietnamTime(m.effectiveDeliveryDeadline)}.`}
          />
        )
      case "SELLER_WEIGHED":
        if (acting && viewer === "SELLER")
          return <SellerCorrectionPanel detail={detail} />
        if (acting && viewer === "BUYER")
          return <BuyerReceiptPanel detail={detail} />
        return (
          <WaitingPanel
            detail={detail}
            title="Hàng đang được vận chuyển"
            text="Bên bán đã cân. Bên mua cân lại khi nhận hàng."
          />
        )
      case "BUYER_RECEIVED":
        return acting && viewer === "BUYER" ? (
          <BuyerDecisionPanel
            detail={detail}
            inspectionPending={inspectionPending}
          />
        ) : (
          <WaitingPanel
            detail={detail}
            title="Chờ bên mua xác nhận"
            text="Bên mua đã cân nhận hàng. Hết hạn mà bên mua không thao tác, hệ thống tự xác nhận theo số đã ghi nhận."
            deadline={
              detail.buyerReceipt
                ? {
                    label: "Hạn bên mua xác nhận",
                    closesAt: detail.buyerReceipt.confirmWindowClosesAt,
                  }
                : undefined
            }
          />
        )
      case "AWAITING_SELLER_RESPONSE":
        return acting && viewer === "SELLER" ? (
          <SellerRespondPanel detail={detail} />
        ) : (
          <WaitingPanel
            detail={detail}
            title="Chờ bên bán phản hồi"
            text={
              detail.flag?.issueType === "QUALITY"
                ? "Bên mua đã báo sai chất lượng. Nếu bên bán không phản hồi, đợt tự chuyển sang giám định độc lập."
                : "Bên mua đã báo thiếu khối lượng. Nếu bên bán không phản hồi, đợt tất toán theo số bên mua cân."
            }
            deadline={
              detail.flag
                ? {
                    label: "Hạn bên bán phản hồi",
                    closesAt: detail.flag.sellerResponseWindowClosesAt,
                  }
                : undefined
            }
          />
        )
      case "CONTESTED":
        return (
          <>
            <InspectionPendingPanel detail={detail} />
            {viewer === "ARBITRATOR" && detail.breachCaseId && (
              <Button
                variant="outline"
                className="w-full sm:w-auto"
                onClick={() =>
                  navigate(
                    `/disputes/${encodeURIComponent(detail.breachCaseId!)}?contractId=${encodeURIComponent(detail.contractId)}`
                  )
                }
              >
                Mở hồ sơ tranh chấp
              </Button>
            )}
          </>
        )
      case "FORCE_MAJEURE_PENDING_REVIEW":
        return (
          <WaitingPanel
            detail={detail}
            title="Chờ xét bất khả kháng"
            text="Bên bán đã báo sự kiện bất khả kháng. Trọng tài đang xem xét bằng chứng; tiền đợt vẫn được giữ."
          />
        )
      case "SETTLED":
        return <SettlementResult detail={detail} />
    }
  })()
  return (
    <>
      {notice}
      {body}
      {detail.remedyDecision && (
        <RemedyDecisionDetails decision={detail.remedyDecision} />
      )}
      {viewer === "OPERATOR" && (
        <Link
          className="text-sm text-primary underline"
          to={`/operations/remedy?contractId=${encodeURIComponent(detail.contractId)}`}
        >
          Gửi lại lệnh tiền lỗi
        </Link>
      )}
      {party && detail.breachCaseId && (
        <BreachCasePanel detail={detail} role={viewer} canAct={acting} />
      )}
      {party && acting && detail.disputeExit && (
        <DisputeExitPanel detail={detail} hash={hash} role={viewer} />
      )}
    </>
  )
}
