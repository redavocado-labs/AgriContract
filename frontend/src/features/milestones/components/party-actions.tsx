import { useState } from "react"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { EvidenceUploader } from "@/shared/ui/evidence-uploader"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { RadioButton, RadioGroup } from "@/shared/ui/radio-group"
import { TextInput } from "@/shared/ui/text-input"
import { useToast } from "@/shared/ui/toast"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import {
  confirmMilestoneClean,
  flagMilestoneIssue,
  recordBuyerReceipt,
  recordSellerWeighed,
  respondToMilestoneFlag,
  type MilestoneDetail,
} from "../api/milestones"
import {
  displayKg,
  milestoneConsent,
  milestoneStatement,
  type IssueType,
} from "../lib/milestone-consent"
import { cleanSettlementPreview, issueTypeLabel } from "../lib/presentation"
import {
  useConsentHash,
  useEvidenceSlot,
  useMilestoneCommand,
} from "../hooks/use-milestone"
import { MilestoneDeltas } from "./delta-rows"
import {
  ConsentCheckbox,
  EvidenceGallery,
  Panel,
  ServerDeadline,
} from "./milestone-parts"

const WEIGHT = /^\d{1,7}(\.\d{1,3})?$/
function weightError(value: string) {
  if (!value.trim()) return "Nhập số kg trên phiếu cân."
  if (!WEIGHT.test(value.trim()))
    return "Nhập số kg, tối đa 3 chữ số thập phân (vd 9700,5 nhập 9700.5)."
  const number = Number(value)
  if (number <= 0) return "Khối lượng phải lớn hơn 0."
  if (number > 1_000_000) return "Tối đa 1.000.000 kg mỗi đợt."
  return ""
}

/**
 * Weight + photos + consent with a review step (D75). Shared by the seller
 * declaration (and its correction) and the buyer receipt step 1.
 */
function WeighForm({
  detail,
  side,
  initial,
}: {
  detail: MilestoneDetail
  side: "SELLER" | "BUYER"
  initial?: { weight: number; evidence: string; certificate: string | null }
}) {
  const toast = useToast()
  const command = useMilestoneCommand(detail)
  const photo = useEvidenceSlot(detail, initial?.evidence)
  const certificate = useEvidenceSlot(detail, initial?.certificate)
  const [weight, setWeight] = useState(initial ? String(initial.weight) : "")
  const [touched, setTouched] = useState(false)
  const [review, setReview] = useState(false)
  const [accepted, setAccepted] = useState(false)
  const m = detail.milestone
  const error = weightError(weight)
  const sameFile = !!photo.fileId && photo.fileId === certificate.fileId
  const action =
    side === "SELLER" ? "DECLARE_SELLER_WEIGHT" : "RECORD_BUYER_RECEIPT"
  const parts =
    review && !error && photo.fileId
      ? side === "SELLER"
        ? {
            action: "DECLARE_SELLER_WEIGHT" as const,
            milestoneId: m.milestoneId,
            sellerDeclaredWeight: weight.trim(),
            sellerEvidenceFileId: photo.fileId,
            deliveryCertificateFileId: certificate.fileId ?? null,
          }
        : {
            action: "RECORD_BUYER_RECEIPT" as const,
            milestoneId: m.milestoneId,
            buyerReceivedWeight: weight.trim(),
            buyerEvidenceFileId: photo.fileId,
            deliveryCertificateFileId: certificate.fileId ?? null,
          }
      : null
  const hash = useConsentHash(parts)
  const waiting = photo.pending || certificate.pending
  const canReview = !error && !!photo.fileId && !waiting && !sameFile
  async function submit() {
    if (!parts || !accepted) return
    const consent = await milestoneConsent(parts)
    const body = {
      ...(side === "SELLER"
        ? {
            sellerDeclaredWeight: Number(weight),
            sellerEvidenceFileId: photo.fileId!,
          }
        : {
            buyerReceivedWeight: Number(weight),
            buyerEvidenceFileId: photo.fileId!,
          }),
      ...(certificate.fileId
        ? { deliveryCertificateFileId: certificate.fileId }
        : {}),
      consent,
    }
    const ok = await command.run(consent.presentedContentHash, (key) =>
      side === "SELLER"
        ? recordSellerWeighed(
            detail.contractId,
            m.milestoneId,
            body as Parameters<typeof recordSellerWeighed>[2],
            key
          )
        : recordBuyerReceipt(
            detail.contractId,
            m.milestoneId,
            body as Parameters<typeof recordBuyerReceipt>[2],
            key
          )
    )
    if (ok) {
      setReview(false)
      setAccepted(false)
      toast.add({
        title:
          side === "SELLER"
            ? initial
              ? "Đã cập nhật số cân"
              : "Đã gửi số cân cho bên mua"
            : "Đã ghi nhận cân nhận hàng",
        type: "success",
      })
    }
  }
  const sellerKg = detail.sellerDeclaration?.sellerDeclaredWeight
  return (
    <div className="space-y-5">
      {!review ? (
        <>
          <div className="grid gap-4 md:grid-cols-2">
            <TextInput
              id={`${side}-weight`}
              label={
                side === "SELLER"
                  ? "Khối lượng cân được"
                  : "Khối lượng bạn cân lại"
              }
              required
              unit="kg"
              inputMode="decimal"
              value={weight}
              onChange={(event) =>
                setWeight(event.target.value.replace(",", "."))
              }
              onBlur={() => setTouched(true)}
              error={touched ? error : undefined}
              hint={
                side === "SELLER"
                  ? `Cam kết ${displayKg(m.committedQuantity)} kg. Ghi đúng số trên phiếu cân, kể cả khi nhiều hơn cam kết.`
                  : `Bên bán khai ${displayKg(sellerKg ?? 0)} kg.`
              }
            />
          </div>
          {!error && (
            <MilestoneDeltas
              committed={m.committedQuantity}
              seller={side === "SELLER" ? Number(weight) : sellerKg}
              buyer={side === "BUYER" ? Number(weight) : null}
            />
          )}
          <div className="grid gap-4 md:grid-cols-2">
            <EvidenceUploader
              id={`${side}-photo`}
              label="Ảnh cân hàng"
              required
              value={photo.item}
              onSelect={photo.select}
              hint="Chụp rõ màn hình cân và lô hàng. Ảnh được kiểm tra an toàn trước khi dùng làm bằng chứng."
            />
            <EvidenceUploader
              id={`${side}-certificate`}
              label="Phiếu giao hàng (không bắt buộc)"
              value={certificate.item}
              onSelect={certificate.select}
              error={
                sameFile
                  ? "Phiếu giao hàng phải là tệp khác ảnh cân."
                  : undefined
              }
              hint="Phiếu xuất kho hoặc biên bản giao nhận, chỉ dùng làm bằng chứng."
            />
          </div>
          {waiting && (
            <Banner variant="info">
              Ảnh đang được kiểm tra an toàn, thường mất vài giây. Bạn có thể
              tiếp tục khi ảnh hiện “Sẵn sàng”.
            </Banner>
          )}
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <Button
              className="w-full sm:w-auto"
              disabled={!canReview}
              onClick={() => {
                setTouched(true)
                setReview(true)
              }}
            >
              Xem lại trước khi gửi
            </Button>
            {!canReview && !waiting && (
              <span className="text-xs text-text-secondary">
                Cần nhập số kg và có ảnh cân đã sẵn sàng.
              </span>
            )}
          </div>
        </>
      ) : (
        <div className="space-y-4">
          <KeyValueList
            items={[
              {
                label: "Khối lượng",
                value: (
                  <span className="font-mono">{displayKg(weight)} kg</span>
                ),
              },
              { label: "Ảnh cân hàng", value: "Đã kiểm tra, sẵn sàng" },
              {
                label: "Phiếu giao hàng",
                value: certificate.fileId
                  ? "Đã kiểm tra, sẵn sàng"
                  : "Không đính kèm",
              },
            ]}
          />
          <EvidenceGallery
            detail={detail}
            role={side}
            fileIds={[photo.fileId, certificate.fileId]}
          />
          <Banner variant={side === "SELLER" ? "info" : "warning"}>
            {side === "SELLER"
              ? "Sau khi gửi, bạn còn sửa được trong 1 tiếng tính từ lần khai đầu, miễn là bên mua chưa ghi nhận cân."
              : "Số cân nhận hàng không sửa được sau khi gửi. Nếu có sai lệch, bạn báo vấn đề ở bước sau."}
          </Banner>
          <ConsentCheckbox
            statement={milestoneStatement(action, { x: weight.trim() })}
            hash={hash}
            checked={accepted}
            onChange={setAccepted}
            disabled={command.busy}
          />
          {command.error && (
            <Banner role="alert" variant="danger">
              {command.error}
            </Banner>
          )}
          <div className="flex flex-col-reverse gap-3 sm:flex-row">
            <Button
              variant="outline"
              className="w-full sm:w-auto"
              disabled={command.busy}
              onClick={() => {
                setReview(false)
                setAccepted(false)
              }}
            >
              Sửa lại
            </Button>
            <Button
              className="w-full sm:w-auto"
              disabled={!accepted || !hash || command.busy}
              onClick={() => void submit()}
            >
              {command.busy
                ? "Đang gửi…"
                : side === "SELLER"
                  ? initial
                    ? "Gửi số cân đã sửa"
                    : "Xác nhận đã cân"
                  : "Ghi nhận cân nhận hàng"}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

export function SellerWeighPanel({ detail }: { detail: MilestoneDetail }) {
  const m = detail.milestone
  const late =
    Date.parse(`${m.effectiveDeliveryDeadline}T23:59:59.999+07:00`) < Date.now()
  return (
    <Panel
      title="Cân hàng trước khi xe chạy"
      description="Nhập số cân thật và chụp ảnh cân. Bên mua sẽ thấy số này khi nhận hàng."
    >
      {late && (
        <Banner variant="warning">
          Đã quá hạn giao {vietnamTime(m.effectiveDeliveryDeadline)}. Bạn vẫn
          cân và giao được khi bên mua chưa báo quá hạn; thời điểm cân thật được
          lưu lại.
        </Banner>
      )}
      <WeighForm detail={detail} side="SELLER" />
    </Panel>
  )
}

export function SellerCorrectionPanel({ detail }: { detail: MilestoneDetail }) {
  const declaration = detail.sellerDeclaration!
  const [editing, setEditing] = useState(false)
  const [closed, setClosed] = useState(
    Date.parse(declaration.correctionWindowClosesAt) <= Date.now()
  )
  return (
    <Panel
      title="Đã cân, đang vận chuyển"
      description={`Lần khai thứ ${declaration.declarationNumber} lúc ${vietnamTime(declaration.declaredAt)}.`}
    >
      <ServerDeadline
        label="Còn sửa được số cân"
        closesAt={declaration.correctionWindowClosesAt}
        onExpire={() => {
          setClosed(true)
          setEditing(false)
        }}
      />
      {editing && !closed ? (
        <WeighForm
          detail={detail}
          side="SELLER"
          initial={{
            weight: declaration.sellerDeclaredWeight,
            evidence: declaration.sellerEvidenceFileId,
            certificate: declaration.deliveryCertificateFileId,
          }}
        />
      ) : (
        <>
          <MilestoneDeltas
            committed={detail.milestone.committedQuantity}
            seller={declaration.sellerDeclaredWeight}
          />
          <EvidenceGallery
            detail={detail}
            role="SELLER"
            fileIds={[
              declaration.sellerEvidenceFileId,
              declaration.deliveryCertificateFileId,
            ]}
          />
          {closed ? (
            <p className="text-sm text-text-secondary">
              Đã hết thời gian sửa. Số cân này là số chính thức của bên bán.
            </p>
          ) : (
            <Button
              variant="outline"
              className="w-full sm:w-auto"
              onClick={() => setEditing(true)}
            >
              Sửa số cân
            </Button>
          )}
        </>
      )}
    </Panel>
  )
}

export function BuyerReceiptPanel({ detail }: { detail: MilestoneDetail }) {
  const declaration = detail.sellerDeclaration!
  return (
    <Panel
      title="Ghi nhận cân khi nhận hàng"
      description="Cân lại lô hàng khi dỡ xuống và chụp ảnh cân. Đây là bước 1; xác nhận đúng hoặc báo vấn đề ở bước 2."
    >
      <div className="space-y-2">
        <h3 className="text-sm font-medium">Bên bán đã cân</h3>
        <KeyValueList
          items={[
            {
              label: "Khối lượng bên bán khai",
              value: (
                <span className="font-mono">
                  {displayKg(declaration.sellerDeclaredWeight)} kg
                </span>
              ),
            },
            {
              label: "Thời điểm cân",
              value: vietnamTime(declaration.declaredAt),
            },
          ]}
        />
        <EvidenceGallery
          detail={detail}
          role="SELLER"
          fileIds={[
            declaration.sellerEvidenceFileId,
            declaration.deliveryCertificateFileId,
          ]}
        />
      </div>
      <WeighForm detail={detail} side="BUYER" />
    </Panel>
  )
}

export function BuyerDecisionPanel({
  detail,
  toleranceRate,
}: {
  detail: MilestoneDetail
  toleranceRate: number | null
}) {
  const toast = useToast()
  const command = useMilestoneCommand(detail)
  const declaration = detail.sellerDeclaration!
  const receipt = detail.buyerReceipt!
  const m = detail.milestone
  const [mode, setMode] = useState<"confirm" | "flag" | null>(null)
  const [issueType, setIssueType] = useState<IssueType | null>(null)
  const [accepted, setAccepted] = useState(false)
  const [closed, setClosed] = useState(
    Date.parse(receipt.confirmWindowClosesAt) <= Date.now()
  )
  const common = {
    milestoneId: m.milestoneId,
    declarationNumber: declaration.declarationNumber,
    sellerDeclaredWeight: declaration.sellerDeclaredWeight,
    buyerReceivedWeight: receipt.buyerReceivedWeight,
  }
  const parts =
    mode === "confirm"
      ? { action: "CONFIRM_CLEAN_RECEIPT" as const, ...common }
      : mode === "flag" && issueType
        ? { action: "FLAG_MILESTONE_ISSUE" as const, ...common, issueType }
        : null
  const hash = useConsentHash(parts)
  const preview =
    toleranceRate == null
      ? null
      : cleanSettlementPreview({
          sellerKg: declaration.sellerDeclaredWeight,
          buyerKg: receipt.buyerReceivedWeight,
          toleranceRate,
          unitPrice: m.effectiveUnitPrice,
          batchAmount: m.batchAmount,
        })
  async function submit() {
    if (!parts || !accepted) return
    const consent = await milestoneConsent(parts)
    const ok = await command.run(consent.presentedContentHash, (key) =>
      parts.action === "CONFIRM_CLEAN_RECEIPT"
        ? confirmMilestoneClean(detail.contractId, m.milestoneId, consent, key)
        : flagMilestoneIssue(
            detail.contractId,
            m.milestoneId,
            parts.issueType,
            consent,
            key
          )
    )
    if (ok)
      toast.add({
        title:
          mode === "confirm"
            ? "Đã xác nhận nhận hàng, đợt được tất toán"
            : "Đã gửi báo vấn đề cho bên bán",
        type: "success",
      })
  }
  const choose = (next: "confirm" | "flag") => {
    setMode(next)
    setAccepted(false)
    command.setError("")
  }
  return (
    <Panel
      title="Xác nhận nhận hàng"
      description="Bước 2: xác nhận lô hàng đạt yêu cầu, hoặc báo vấn đề để bên bán phản hồi."
    >
      <ServerDeadline
        label="Hạn xác nhận"
        closesAt={receipt.confirmWindowClosesAt}
        onExpire={() => setClosed(true)}
      />
      <MilestoneDeltas
        committed={m.committedQuantity}
        seller={declaration.sellerDeclaredWeight}
        buyer={receipt.buyerReceivedWeight}
      />
      {preview && (
        <div className="space-y-3 rounded-md border border-border bg-page-bg p-4">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="text-sm text-text-secondary">
              Giải ngân dự kiến cho bên bán
            </span>
            <MoneyDisplay amount={preview.sellerAmount} emphasis="large" />
          </div>
          <p className="text-sm text-text-secondary">
            {preview.withinTolerance
              ? `Hao hụt ${displayKg(preview.loss)} kg nằm trong dung sai ${displayKg(preview.tolerance)} kg nên tính theo số bên bán khai: ${displayKg(preview.billable)} kg × ${Number(m.effectiveUnitPrice).toLocaleString("vi-VN")} ₫/kg.`
              : `Hao hụt ${displayKg(preview.loss)} kg vượt dung sai ${displayKg(preview.tolerance)} kg: phần trong dung sai bên mua chịu, phần vượt chia đôi. Khối lượng tính tiền ${displayKg(preview.billable)} kg.`}
          </p>
          {preview.buyerRefund && (
            <p className="text-sm">
              Hoàn về bên mua:{" "}
              <MoneyDisplay amount={preview.buyerRefund} className="text-sm" />
            </p>
          )}
          <p className="text-xs text-text-muted">
            Số dự kiến để tham khảo. Số tiền chính thức do hệ thống tính khi tất
            toán.
          </p>
        </div>
      )}
      {closed ? (
        <Banner variant="info">
          Đã hết hạn xác nhận. Hệ thống tự xác nhận theo số đã ghi nhận nếu đợt
          không có giám định đang chờ.
        </Banner>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <ChoiceCard
              icon="check_circle"
              title="Hàng đạt, xác nhận đúng"
              text="Đợt được tất toán ngay theo khối lượng tính tiền."
              selected={mode === "confirm"}
              onClick={() => choose("confirm")}
            />
            <ChoiceCard
              icon="flag"
              title="Báo vấn đề"
              text="Tiền đợt vẫn được giữ tới khi bên bán phản hồi hoặc có kết quả giám định."
              selected={mode === "flag"}
              onClick={() => choose("flag")}
            />
          </div>
          {mode === "flag" && (
            <div className="space-y-2">
              <p id="issue-type" className="text-[13px]">
                Loại vấn đề <span className="text-danger">*</span>
              </p>
              <RadioGroup
                aria-labelledby="issue-type"
                value={issueType ?? ""}
                onValueChange={(value) => {
                  setIssueType(value as IssueType)
                  setAccepted(false)
                }}
                className="gap-3"
              >
                <RadioButton
                  value="QUANTITY"
                  label={
                    <span>
                      <span className="block">{issueTypeLabel.QUANTITY}</span>
                      <span className="block text-xs text-text-secondary">
                        Chỉ tranh cãi số cân. Bên bán im lặng quá hạn thì đợt
                        tất toán theo số bạn cân.
                      </span>
                    </span>
                  }
                />
                <RadioButton
                  value="QUALITY"
                  label={
                    <span>
                      <span className="block">{issueTypeLabel.QUALITY}</span>
                      <span className="block text-xs text-text-secondary">
                        Giám định kiểm cả khối lượng và chỉ tiêu. Bên bán im
                        lặng thì vẫn chuyển giám định độc lập.
                      </span>
                    </span>
                  }
                />
              </RadioGroup>
            </div>
          )}
          {parts && (
            <ConsentCheckbox
              statement={
                parts.action === "CONFIRM_CLEAN_RECEIPT"
                  ? milestoneStatement("CONFIRM_CLEAN_RECEIPT", {
                      s: declaration.sellerDeclaredWeight,
                      b: receipt.buyerReceivedWeight,
                    })
                  : milestoneStatement("FLAG_MILESTONE_ISSUE", {
                      issueType: parts.issueType,
                    })
              }
              hash={hash}
              checked={accepted}
              onChange={setAccepted}
              disabled={command.busy}
            />
          )}
          {command.error && (
            <Banner role="alert" variant="danger">
              {command.error}
            </Banner>
          )}
          {parts && (
            <Button
              className="w-full sm:w-auto"
              variant={mode === "flag" ? "destructive-solid" : "default"}
              disabled={!accepted || !hash || command.busy}
              onClick={() => void submit()}
            >
              {command.busy
                ? "Đang gửi…"
                : mode === "confirm"
                  ? "Xác nhận đúng và tất toán"
                  : "Gửi báo vấn đề"}
            </Button>
          )}
        </>
      )}
    </Panel>
  )
}

function ChoiceCard({
  icon,
  title,
  text,
  selected,
  onClick,
}: {
  icon: string
  title: string
  text: string
  selected: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={`flex items-start gap-3 rounded-md bg-surface p-4 text-left ${selected ? "border-[1.5px] border-primary" : "border border-border hover:bg-surface-muted"}`}
    >
      <span
        className={`ms text-[22px] ${selected ? "text-primary" : "text-text-secondary"}`}
      >
        {icon}
      </span>
      <span className="space-y-1">
        <span className="block text-sm font-medium">{title}</span>
        <span className="block text-xs text-text-secondary">{text}</span>
      </span>
    </button>
  )
}

export function SellerRespondPanel({ detail }: { detail: MilestoneDetail }) {
  const toast = useToast()
  const command = useMilestoneCommand(detail)
  const flag = detail.flag!
  const declaration = detail.sellerDeclaration!
  const receipt = detail.buyerReceipt!
  const [accepted, setAccepted] = useState(false)
  const [closed, setClosed] = useState(
    Date.parse(flag.sellerResponseWindowClosesAt) <= Date.now()
  )
  const parts = {
    action: "CONTEST_MILESTONE_FLAG" as const,
    milestoneId: detail.milestone.milestoneId,
    sellerDeclaredWeight: declaration.sellerDeclaredWeight,
    buyerReceivedWeight: receipt.buyerReceivedWeight,
    issueType: flag.issueType,
  }
  const hash = useConsentHash(parts)
  async function submit() {
    const consent = await milestoneConsent(parts)
    const ok = await command.run(consent.presentedContentHash, (key) =>
      respondToMilestoneFlag(
        detail.contractId,
        detail.milestone.milestoneId,
        consent,
        key
      )
    )
    if (ok)
      toast.add({
        title: "Đã phản đối, đợt chuyển sang giám định độc lập",
        type: "success",
      })
  }
  return (
    <Panel
      title="Phản hồi báo vấn đề của bên mua"
      description={`Bên mua báo ${issueTypeLabel[flag.issueType].toLowerCase()} lúc ${vietnamTime(flag.flaggedAt)}.`}
    >
      <ServerDeadline
        label="Hạn phản hồi"
        closesAt={flag.sellerResponseWindowClosesAt}
        onExpire={() => setClosed(true)}
      />
      <Banner variant="warning">
        {flag.issueType === "QUANTITY"
          ? `Nếu bạn không phản hồi trước hạn, đợt tất toán theo số bên mua cân (${displayKg(receipt.buyerReceivedWeight)} kg) và bạn chịu toàn bộ hao hụt.`
          : "Nếu bạn không phản hồi trước hạn, hệ thống vẫn tự chuyển đợt sang giám định độc lập. Bạn không bị coi là thua chỉ vì im lặng."}
      </Banner>
      <MilestoneDeltas
        committed={detail.milestone.committedQuantity}
        seller={declaration.sellerDeclaredWeight}
        buyer={receipt.buyerReceivedWeight}
      />
      <div className="space-y-2">
        <h3 className="text-sm font-medium">Ảnh bên mua gửi</h3>
        <EvidenceGallery
          detail={detail}
          role="BUYER"
          fileIds={[
            receipt.buyerEvidenceFileId,
            receipt.deliveryCertificateFileId,
          ]}
        />
      </div>
      {closed ? (
        <Banner variant="info">
          Đã hết hạn phản hồi. Tải lại để xem kết quả xử lý.
        </Banner>
      ) : (
        <>
          <div className="space-y-1 rounded-md border border-border bg-page-bg p-4">
            <p className="text-sm font-medium">
              Phản đối và yêu cầu giám định Level 1.5
            </p>
            <p className="text-sm text-text-secondary">
              Đơn vị giám định độc lập kiểm tra lô hàng. Kết quả giám định là
              căn cứ tất toán; bên không được kết quả ủng hộ chịu phí giám định.
              Tiền đợt vẫn được giữ trong thời gian này.
            </p>
          </div>
          <ConsentCheckbox
            statement={milestoneStatement("CONTEST_MILESTONE_FLAG")}
            hash={hash}
            checked={accepted}
            onChange={setAccepted}
            disabled={command.busy}
          />
          {command.error && (
            <Banner role="alert" variant="danger">
              {command.error}
            </Banner>
          )}
          <Button
            className="w-full sm:w-auto"
            disabled={!accepted || !hash || command.busy}
            onClick={() => void submit()}
          >
            {command.busy ? "Đang gửi…" : "Phản đối và yêu cầu giám định"}
          </Button>
        </>
      )}
    </Panel>
  )
}
