import { useEffect, useRef, useState } from "react"
import { useParams } from "react-router-dom"
import { useContract } from "@/features/contracts/hooks/use-contracts"
import { ContractsLayout } from "@/features/contracts/components/contracts-layout"
import {
  ContractBadge,
  ContractFailure,
  ContractLoading,
} from "@/features/contracts/components/contract-state"
import { RemedyDecisionDetails } from "@/features/contracts/components/remedy-decision"
import { ContractTerms } from "@/features/contracts/components/contract-terms"
import { SigningPanel } from "@/features/contracts/components/signing-panel"
import { NegotiationForm } from "@/features/contracts/components/negotiation-form"
import { activationMessage } from "@/features/contracts/types/presentation"
import { capabilities } from "@/features/contracts/api/capabilities"
import { WithdrawOffer } from "@/features/contracts/components/withdraw-offer"
import { ContractMilestones } from "@/features/milestones/components/contract-milestones"
import { parseApiError } from "@/shared/api/errors"
import { useAuthStore } from "@/shared/auth/auth-store"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { ContentHashDisplay } from "@/shared/ui/content-hash-display"
import { useToast } from "@/shared/ui/toast"

export function ContractDetailPage() {
  const { id = "" } = useParams()
  const userId = useAuthStore((state) => state.userId)
  return <Detail key={JSON.stringify([userId, id])} id={id} />
}
function Detail({ id }: { id: string }) {
  const query = useContract(id)
  const contract = query.data
  const readStatus = parseApiError(query.error).status
  const showReadFailure =
    query.isError &&
    (contract?.status !== "SIGNED" || [401, 403, 404].includes(readStatus))
  const previous = useRef<{ hash: string | null; status: string } | null>(null)
  const [changed, setChanged] = useState(false)
  const toast = useToast()
  useEffect(() => {
    if (!contract) return
    if (previous.current?.hash && previous.current.hash !== contract.hash)
      setChanged(true)
    if (previous.current?.status === "SIGNED" && contract.status === "ACTIVE")
      toast.add({ title: "Hợp đồng đã có hiệu lực", type: "success" })
    previous.current = { hash: contract.hash, status: contract.status }
  }, [contract, toast])
  return (
    <ContractsLayout title="Chi tiết hợp đồng">
      {query.isPending ? (
        <ContractLoading />
      ) : showReadFailure ? (
        <ContractFailure
          error={query.error}
          retry={() => void query.refetch()}
        />
      ) : (
        contract && (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <span className="break-all font-mono text-sm">{contract.id}</span>
              <ContractBadge status={contract.status} />
              <div className="flex flex-wrap gap-3 sm:ml-auto">
                <Button
                  variant="outline"
                  disabled={query.isFetching}
                  onClick={() => void query.refetch()}
                >
                  Tải lại trạng thái
                </Button>
                <WithdrawOffer contract={contract} />
              </div>
            </div>
            {changed && (
              <Banner variant="warning">
                Điều khoản vừa thay đổi. Hãy đọc lại nội dung và mã vân tay mới;
                bản đề xuất và xác nhận cũ đã được bỏ.
              </Banner>
            )}
            {["OFFERED", "NEGOTIATING"].includes(contract.status) && (
              <Banner variant="warning">
                CHƯA CÓ HIỆU LỰC — thông tin điều khoản dưới đây không phải PDF
                hợp đồng do máy chủ phát hành.
              </Banner>
            )}
            {contract.terminalDecision ? (
              <>
                <Banner
                  variant={
                    contract.terminalDecision.moneyCleared ? "success" : "info"
                  }
                >
                  {contract.terminalDecision.moneyCleared
                    ? contract.terminalDecision.targetStatus === "SETTLED"
                      ? "Đã hoàn tất"
                      : "Đã chấm dứt hợp đồng"
                    : "Đang chờ trả cọc/tiền về"}
                </Banner>
                <RemedyDecisionDetails
                  decision={contract.terminalDecision.remedyDecision}
                  title="Quyết định tiền cuối hợp đồng"
                />
              </>
            ) : (
              <Banner
                variant={
                  contract.status === "ACTIVE" || contract.status === "SETTLED"
                    ? "success"
                    : "info"
                }
              >
                {contract.status === "SETTLED"
                  ? "Đã hoàn tất"
                  : activationMessage(contract.status)}
              </Banner>
            )}
            {query.activationCheck === "pending" && (
              <p role="status" className="text-xs text-text-secondary">
                Tự kiểm tra trạng thái mỗi 5 giây, tối đa 2 phút.
              </p>
            )}
            {(query.activationCheck === "timed-out" ||
              query.activationCheck === "interrupted") && (
              <Banner variant="warning" role="status">
                {query.activationCheck === "timed-out"
                  ? "Đã hết thời gian kiểm tra tự động."
                  : "Không tải được trạng thái mới từ máy chủ."}{" "}
                Chưa có xác nhận kích hoạt. Dùng “Tải lại trạng thái” để kiểm tra.
              </Banner>
            )}
            {contract.generation === "legacy" && (
              <Banner variant="info">
                Hợp đồng theo định dạng cũ — chỉ xem thông tin, không đàm phán
                hoặc ký theo quy trình mới.
              </Banner>
            )}
            {contract.hash ? (
              <div className="rounded-md border border-border bg-surface p-4">
                <p className="mb-2 text-sm">Mã vân tay nội dung từ máy chủ</p>
                <ContentHashDisplay hash={contract.hash} />
                <p className="mt-2 text-xs text-text-secondary">
                  Mẫu hợp đồng{" "}
                  {String(
                    contract.terms.contractTemplateCode ?? "VN_COFFEE_B2B"
                  )}{" "}
                  phiên bản{" "}
                  {String(
                    contract.terms.contractTemplateVersion ?? "chưa xác định"
                  )}
                  . Mã vân tay đổi mỗi lần điều khoản được sửa.
                </p>
              </div>
            ) : (
              <p className="text-sm text-text-secondary">
                Chưa có mã vân tay nội dung.
              </p>
            )}
            <ContractMilestones contract={contract} />
            <ContractTerms contract={contract} />
            {contract.generation === "coffee" &&
              ["OFFERED", "NEGOTIATING"].includes(contract.status) && (
                <>
                  <NegotiationForm
                    key={`terms-${contract.hash}`}
                    contract={contract}
                  />
                  <SigningPanel
                    key={`sign-${contract.hash}`}
                    contract={contract}
                  />
                </>
              )}
            <section className="space-y-3 rounded-md border border-border bg-surface p-4">
              <h2 className="text-lg">Tài liệu & lịch sử</h2>
              {!capabilities.documents.enabled && (
                <Banner variant="info">{capabilities.documents.reason}</Banner>
              )}
              <div className="flex flex-wrap gap-3">
                <Button
                  disabled={!capabilities.documents.enabled}
                  variant="outline"
                >
                  Xem PDF nháp
                </Button>
                <Button
                  disabled={!capabilities.documents.enabled}
                  variant="outline"
                >
                  Tải PDF đã ký
                </Button>
              </div>
            </section>
          </>
        )
      )}
    </ContractsLayout>
  )
}
