import type { RemedyDecision } from "../api/remedy"
import { DataTable, type DataTableColumn } from "@/shared/ui/data-table"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { RecordCard } from "@/shared/ui/record-card"
import { StatusBadge } from "@/shared/ui/status-badge"

const roleLabel = {
  BUYER: "Bên mua",
  SELLER: "Bên bán",
  SPLIT: "Hai bên chia đôi",
}
const fundLabel = {
  BUYER_DEPOSIT: "Cọc bên mua",
  SELLER_DEPOSIT: "Cọc bên bán",
  MILESTONE_PAYMENT: "Tiền đợt giao",
}
const typeLabel = {
  NONE: "Không phát lệnh tiền",
  PAYMENT_SETTLEMENT: "Giải ngân",
  PAYMENT_REFUND: "Hoàn tiền",
  DEPOSIT_FORFEITURE: "Khấu trừ cọc",
  CONTRACTUAL_PENALTY: "Phạt hợp đồng",
  DAMAGES_COMPENSATION: "Bồi thường thiệt hại",
}
type Leg = RemedyDecision["remedyLegs"][number]
const columns: DataTableColumn<Leg>[] = [
  {
    key: "type",
    header: "Lệnh tiền",
    render: (row) => typeLabel[row.remedyType],
  },
  {
    key: "fund",
    header: "Nguồn tiền",
    render: (row) => fundLabel[row.fundType],
  },
  { key: "role", header: "Bên nhận", render: (row) => roleLabel[row.role] },
  {
    key: "amount",
    header: "Số tiền",
    align: "right",
    width: "minmax(11rem, 1fr)",
    render: (row) => <MoneyDisplay amount={row.amount} />,
  },
]

export function RemedyDecisionDetails({
  decision,
  title = "Quyết định chế tài",
}: {
  decision: RemedyDecision
  title?: string
}) {
  const pending = decision.status === "PENDING_CONTEST"
  return (
    <section className="space-y-4 rounded-md border border-border bg-surface p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg">{title}</h2>
        <StatusBadge variant={pending ? "warning" : "success"}>
          {pending ? "Chờ hết hạn phản đối" : "Đã chốt quyết định"}
        </StatusBadge>
      </div>
      <KeyValueList
        items={[
          ...(decision.finalBreachingRole
            ? [
                {
                  label: "Bên bị quy lỗi",
                  value: roleLabel[decision.finalBreachingRole],
                },
              ]
            : []),
          ...(decision.inspectionFeeParty
            ? [
                {
                  label: "Bên chịu phí giám định",
                  value: roleLabel[decision.inspectionFeeParty],
                },
              ]
            : []),
          ...(/[1-9]/.test(decision.penaltyAmount)
            ? [
                {
                  label: "Tiền phạt",
                  value: <MoneyDisplay amount={decision.penaltyAmount} />,
                },
              ]
            : []),
          ...(/[1-9]/.test(decision.penaltyDebtAmount)
            ? [
                {
                  label: "Khoản phạt chưa thu được, dùng làm bằng chứng",
                  value: <MoneyDisplay amount={decision.penaltyDebtAmount} />,
                },
              ]
            : []),
        ]}
      />
      {decision.remedyLegs.length > 0 && (
        <>
          <DataTable
            className="hidden xl:block"
            columns={columns}
            rows={decision.remedyLegs}
            rowKey={(leg) => leg.remedyLegId}
          />
          <div className="space-y-2 xl:hidden">
            {decision.remedyLegs.map((leg) => (
              <RecordCard
                key={leg.remedyLegId}
                badge={
                  <StatusBadge variant="neutral">
                    {typeLabel[leg.remedyType]}
                  </StatusBadge>
                }
                highlight={<MoneyDisplay amount={leg.amount} />}
                rows={[
                  { label: "Nguồn tiền", value: fundLabel[leg.fundType] },
                  { label: "Bên nhận", value: roleLabel[leg.role] },
                ]}
              />
            ))}
          </div>
        </>
      )}
    </section>
  )
}
