import {
  DeltaComparisonBlock,
  type DeltaRow,
} from "@/shared/ui/delta-comparison-block"
import { displayKg } from "../lib/milestone-consent"

const kg = (value: number) => `${displayKg(value)} kg`
const diff = (value: number) =>
  value === 0
    ? "0 kg"
    : `${value < 0 ? "− " : "+ "}${displayKg(Math.abs(value))} kg`

/** Delta 1 (committed -> seller) and Delta 2 (seller -> buyer), never merged. */
export function MilestoneDeltas({
  committed,
  seller,
  buyer,
}: {
  committed: number
  seller?: number | null
  buyer?: number | null
}) {
  const rows: DeltaRow[] = []
  if (seller != null)
    rows.push({
      key: "delta-1",
      label: "Chênh lệch cam kết",
      before: kg(committed),
      after: kg(seller),
      delta: diff(seller - committed),
      consequence: seller < committed ? "danger" : "neutral",
      consequenceLabel:
        seller < committed ? "Có thể tính phạt" : "Không tính phạt",
    })
  if (seller != null && buyer != null)
    rows.push({
      key: "delta-2",
      label: "Hao hụt vận chuyển",
      before: kg(seller),
      after: kg(buyer),
      delta: diff(buyer - seller),
      consequence: "neutral",
      consequenceLabel: "Không tính phạt",
    })
  if (!rows.length) return null
  return (
    <>
      <DeltaComparisonBlock rows={rows} className="hidden md:block" />
      <DeltaComparisonBlock rows={rows} layout="cards" className="md:hidden" />
    </>
  )
}
