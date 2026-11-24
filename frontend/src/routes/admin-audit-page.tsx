import { useState } from "react"
import { useSearchParams } from "react-router-dom"
import { ContractsLayout } from "@/features/contracts/components/contracts-layout"
import {
  AdminContractAuditPanel,
  AdminGlobalAuditPanel,
} from "@/features/audit"
import { Banner } from "@/shared/ui/banner"
import { TabsUnderline } from "@/shared/ui/tabs-underline"

type AdminAuditTab = "global" | "contract"

export function AdminAuditPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialContract = searchParams.get("contractId") ?? ""
  const initialTab: AdminAuditTab =
    searchParams.get("tab") === "contract" || initialContract
      ? "contract"
      : "global"
  const [tab, setTab] = useState<AdminAuditTab>(initialTab)
  const [contractId, setContractId] = useState(initialContract)

  function switchTab(next: string) {
    const resolved = (next === "contract" ? "contract" : "global") as AdminAuditTab
    setTab(resolved)
    setSearchParams((prev) => {
      const copy = new URLSearchParams(prev)
      copy.set("tab", resolved)
      return copy
    }, { replace: true })
  }

  function openContract(id: string) {
    setContractId(id)
    setTab("contract")
    setSearchParams({ tab: "contract", contractId: id }, { replace: true })
  }

  function changeContractId(id: string) {
    setContractId(id)
    setSearchParams(
      id ? { tab: "contract", contractId: id } : { tab: "contract" },
      { replace: true }
    )
  }

  return (
    <ContractsLayout
      title="Nhật ký bằng chứng"
      crumbs={[{ label: "Nhật ký bằng chứng" }]}
    >
      <Banner variant="info">
        Mỗi lần bạn mở nhật ký, hệ thống ghi lại một bản ghi không xoá được.
      </Banner>
      <TabsUnderline
        tabs={[
          { key: "global", label: "Toàn hệ thống" },
          { key: "contract", label: "Theo hợp đồng" },
        ]}
        value={tab}
        onChange={switchTab}
        scrollable
      />
      {tab === "global" ? (
        <AdminGlobalAuditPanel onOpenContract={openContract} />
      ) : (
        <AdminContractAuditPanel
          contractId={contractId}
          onChangeContractId={changeContractId}
        />
      )}
    </ContractsLayout>
  )
}
