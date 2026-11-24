import { useNavigate } from "react-router-dom"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { RecordCard } from "@/shared/ui/record-card"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import type { Contract } from "@/features/contracts"
import { displayKg } from "../lib/milestone-consent"
import { fundingLabel } from "../lib/presentation"
import { MilestoneBadge, Panel } from "./milestone-parts"

/** The delivery batches of an ACTIVE (or later) contract, each opening its milestone page. */
export function ContractMilestones({ contract }: { contract: Contract }) {
  const navigate = useNavigate()
  if (!contract.milestones.length) return null
  const milestones = [...contract.milestones].sort(
    (a, b) => a.milestoneIndex - b.milestoneIndex
  )
  return (
    <Panel
      title="Đợt giao"
      description="Mỗi đợt được khoá tiền, cân, nhận và tất toán riêng."
    >
      <div className="grid gap-3 md:grid-cols-2">
        {milestones.map((m) => (
          <RecordCard
            key={m.milestoneId}
            code={`Đợt ${m.milestoneIndex}`}
            badge={<MilestoneBadge status={m.status} />}
            rows={[
              {
                label: "Khối lượng",
                value: `${displayKg(m.committedQuantity)} kg`,
              },
              {
                label: "Tiền đợt",
                value: m.batchAmount ? (
                  <MoneyDisplay amount={m.batchAmount} className="text-sm" />
                ) : (
                  fundingLabel[m.fundingStatus]
                ),
              },
              {
                label: "Hạn giao",
                value: vietnamTime(m.effectiveDeliveryDeadline),
              },
            ]}
            chevron
            onClick={() =>
              navigate(
                `/contracts/${encodeURIComponent(contract.id)}/milestones/${encodeURIComponent(m.milestoneId)}`
              )
            }
          />
        ))}
      </div>
    </Panel>
  )
}
