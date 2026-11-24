import { useState } from "react"
import {
  endSettlementProposal,
  type DisputeExitIntent,
  type MilestoneDetail,
} from "../api/milestones"
import { useDisputeOtp } from "../hooks/use-dispute-otp"
import { useMilestoneCommand } from "../hooks/use-milestone"
import {
  concessionStatement,
  settlementStatement,
} from "../lib/dispute-consent"
import { ConsentCheckbox, Panel, ServerDeadline } from "./milestone-parts"
import type { ParticipantRole } from "@/features/contracts/types/contract"
import { vietnamTime } from "@/shared/lib/vietnam-time"
import { Banner } from "@/shared/ui/banner"
import { Button } from "@/shared/ui/button"
import { Countdown } from "@/shared/ui/countdown"
import { KeyValueList } from "@/shared/ui/key-value-list"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { OTPInput } from "@/shared/ui/otp-input"
import { Stepper } from "@/shared/ui/stepper"
import { TextInput } from "@/shared/ui/text-input"
import { useToast } from "@/shared/ui/toast"

const roleLabel = { BUYER: "Bên mua", SELLER: "Bên bán" }

export function DisputeExitPanel({
  detail,
  hash,
  role,
}: {
  detail: MilestoneDetail
  hash: string | null
  role: ParticipantRole
}) {
  const exit = detail.disputeExit
  const [choice, setChoice] = useState<DisputeExitIntent["kind"]>()
  const [amount, setAmount] = useState("")
  const request = useMilestoneCommand(detail, "dispute-exit")
  const toast = useToast()
  if (!exit?.concedeAllowed && !exit?.settlementAllowed) return null

  const proposal = exit.openProposal
  const proposedHere = proposal?.proposedByRole === role
  const sellerAmount =
    role === "SELLER" ? exit.buyerPositionAmount : exit.sellerPositionAmount
  const refund =
    role === "SELLER"
      ? exit.buyerPositionRefundAmount
      : exit.sellerPositionRefundAmount
  const parsedAmount = /^(?:0|[1-9]\d{0,15})$/.test(amount)
    ? `${amount}.00`
    : null
  const settlementAmount =
    proposal?.agreedSellerEntitlementAmount ?? parsedAmount
  const intent: DisputeExitIntent | undefined =
    choice === "concede" && exit.concedeAllowed && refund
      ? { kind: "concede" }
      : choice === "settlement" &&
          exit.settlementAllowed &&
          !proposedHere &&
          settlementAmount
        ? { kind: "settlement", amount: settlementAmount }
        : undefined

  async function endProposal() {
    if (!proposal) return
    const ok = await request.run(`end-${proposal.proposedAt}`, (key) =>
      endSettlementProposal(detail, key)
    )
    if (ok) {
      setChoice(undefined)
      setAmount("")
      toast.add({
        title: proposedHere ? "Đã rút đề xuất" : "Đã từ chối đề xuất",
        type: "success",
      })
    }
  }

  return (
    <Panel
      title="Kết thúc tranh chấp"
      description="Bạn có thể nhận vị thế của bên kia hoặc thỏa thuận số tiền bên bán nhận."
    >
      <KeyValueList
        items={[
          {
            label: "Tiền bên bán nhận theo vị thế bên mua",
            stackOnNarrow: true,
            value: <MoneyDisplay amount={exit.buyerPositionAmount} />,
          },
          {
            label: "Tiền bên bán nhận theo vị thế bên bán",
            stackOnNarrow: true,
            value: <MoneyDisplay amount={exit.sellerPositionAmount} />,
          },
        ]}
      />
      {proposal && (
        <div className="space-y-2 rounded-md border border-border bg-page-bg p-3">
          <h3 className="text-sm font-medium">
            Đề xuất của {roleLabel[proposal.proposedByRole].toLowerCase()}
          </h3>
          <KeyValueList
            items={[
              {
                label: "Bên bán nhận",
                stackOnNarrow: true,
                value: (
                  <MoneyDisplay
                    amount={proposal.agreedSellerEntitlementAmount}
                  />
                ),
              },
              ...(proposal.agreedBuyerRefundAmount
                ? [
                    {
                      label: "Bên mua được hoàn",
                      stackOnNarrow: true,
                      value: (
                        <MoneyDisplay
                          amount={proposal.agreedBuyerRefundAmount}
                        />
                      ),
                    },
                  ]
                : []),
              {
                label: "Đề xuất lúc",
                stackOnNarrow: true,
                value: vietnamTime(proposal.proposedAt),
              },
            ]}
          />
          <Button
            variant="outline"
            disabled={request.busy}
            onClick={() => void endProposal()}
          >
            {proposedHere ? "Rút đề xuất" : "Từ chối đề xuất"}
          </Button>
        </div>
      )}
      {request.error && (
        <Banner role="alert" variant="danger">
          {request.error}
        </Banner>
      )}
      {exit.concedeAllowed && !refund && (
        <Banner variant="info">
          Chưa có số tiền hoàn từ máy chủ để xác nhận nhận lỗi.
        </Banner>
      )}
      {!hash && (
        <Banner variant="info">
          Chưa có mã vân tay hợp đồng để gửi xác nhận.
        </Banner>
      )}
      <div className="flex flex-wrap gap-2">
        {exit.concedeAllowed && refund && hash && (
          <Button
            variant={choice === "concede" ? "default" : "outline"}
            disabled={request.busy}
            onClick={() => setChoice("concede")}
          >
            {role === "BUYER" ? "Rút báo lỗi" : "Nhận lỗi"}
          </Button>
        )}
        {exit.settlementAllowed && !proposedHere && hash && (
          <Button
            variant={choice === "settlement" ? "default" : "outline"}
            disabled={request.busy}
            onClick={() => setChoice("settlement")}
          >
            {proposal ? "Chấp nhận đề xuất" : "Đề xuất thỏa thuận"}
          </Button>
        )}
      </div>
      {choice === "settlement" && !proposal && exit.settlementAllowed && (
        <TextInput
          id="settlement-amount"
          label="Số tiền đề xuất cho bên bán"
          unit="₫"
          inputMode="numeric"
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          error={
            amount && !parsedAmount
              ? "Nhập số tiền theo đơn vị đồng, tối đa 16 chữ số."
              : undefined
          }
        />
      )}
      {intent && hash && (
        <DisputeOtp
          key={JSON.stringify([
            hash,
            intent,
            proposal?.proposedAt,
            sellerAmount,
            refund,
          ])}
          detail={detail}
          intent={intent}
          hash={hash}
          statement={
            intent.kind === "concede"
              ? concessionStatement(role, sellerAmount, refund!)
              : settlementStatement(intent.amount)
          }
          success={
            intent.kind === "settlement" && !proposal
              ? "Đã gửi đề xuất thỏa thuận"
              : "Đã xác nhận kết thúc tranh chấp"
          }
          onDone={() => {
            setChoice(undefined)
            setAmount("")
          }}
        />
      )}
    </Panel>
  )
}

function DisputeOtp({
  detail,
  intent,
  hash,
  statement,
  success,
  onDone,
}: {
  detail: MilestoneDetail
  intent: DisputeExitIntent
  hash: string
  statement: string
  success: string
  onDone: () => void
}) {
  const flow = useDisputeOtp(detail, intent, hash)
  const toast = useToast()
  const [accepted, setAccepted] = useState(false)
  const [code, setCode] = useState("")
  const [expiredId, setExpiredId] = useState<string>()
  const [now, setNow] = useState(Date.now)
  const challenge = flow.challenge
  const expired =
    !!challenge &&
    (expiredId === challenge.otpId || Date.parse(challenge.expiresAt) <= now)

  async function send() {
    if (!accepted || flow.busy) return
    setCode("")
    await flow.send()
    setNow(Date.now())
  }
  async function verify() {
    if (!accepted || expired || !/^\d{6}$/.test(code)) return
    if (await flow.verify(code)) {
      toast.add({ title: success, type: "success" })
      onDone()
    }
  }
  return (
    <div className="space-y-3 border-t border-border pt-4">
      <Stepper
        steps={["Xem và đồng ý", "Xác thực", "Nhập OTP"]}
        currentStep={challenge ? 3 : accepted ? 2 : 1}
      />
      <ConsentCheckbox
        statement={statement}
        version="1.0"
        hash={hash}
        checked={accepted}
        onChange={setAccepted}
        disabled={flow.busy || !!challenge}
      />
      {flow.error && (
        <Banner role="alert" variant="danger">
          {flow.error}
        </Banner>
      )}
      {challenge && (
        <div className="space-y-3">
          <p className="text-sm">Nhập mã OTP được gửi đến email của bạn.</p>
          <ServerDeadline
            key={challenge.otpId}
            label="Hạn nhập OTP"
            closesAt={challenge.expiresAt}
            onExpire={() => setExpiredId(challenge.otpId)}
          />
          <fieldset disabled={flow.busy || expired} aria-label="Nhập mã OTP">
            <OTPInput
              value={code}
              onChange={setCode}
              className="gap-1 [&_input]:min-w-0 [&_input]:max-w-10 [&_input]:flex-1"
            />
          </fieldset>
          <Button
            disabled={flow.busy || expired || code.length !== 6}
            onClick={() => void verify()}
          >
            Xác nhận OTP
          </Button>
        </div>
      )}
      <Button
        variant={challenge ? "outline" : "default"}
        disabled={
          !accepted || flow.busy || flow.complete || now < flow.resendAt
        }
        onClick={() => void send()}
      >
        {challenge ? "Gửi lại mã" : "Gửi mã OTP"}
      </Button>
      {now < flow.resendAt && (
        <Countdown
          key={flow.resendAt}
          seconds={Math.ceil((flow.resendAt - now) / 1000)}
          onExpire={() => setNow(Date.now())}
          className="text-sm"
        />
      )}
    </div>
  )
}
