import { useState } from "react"
import { useFreshLogin } from "@/features/auth"
import type { Challenge } from "@/features/contracts"
import { parseApiError } from "@/shared/api/errors"
import {
  initiateDisputeExit,
  verifyDisputeExit,
  type DisputeExitIntent,
  type MilestoneDetail,
} from "../api/milestones"
import { useMilestoneCommand } from "./use-milestone"

const resendDelay = 60_000
const invalidChallenge = new Set([
  "OTP_EXPIRED",
  "OTP_ALREADY_USED",
  "OTP_LOCKED",
  "OTP_CHALLENGE_NOT_FOUND",
  "OTP_CONTRACT_MISMATCH",
  "OTP_TERMS_CHANGED",
  "OTP_CHALLENGE_NOT_ACTIVE",
  "OTP_ATTEMPTS_EXHAUSTED",
  "SETTLEMENT_PROPOSAL_CONFLICT",
  "DISPUTE_EXIT_NOT_ALLOWED",
])

export function useDisputeOtp(
  detail: MilestoneDetail,
  intent: DisputeExitIntent,
  hash: string
) {
  const fresh = useFreshLogin()
  const command = useMilestoneCommand(detail, "dispute-otp")
  const [challenge, setChallenge] = useState<Challenge>()
  const [resendAt, setResendAt] = useState(0)
  const [complete, setComplete] = useState(false)
  const action = `dispute-${intent.kind}`
  const common = {
    action,
    contractId: detail.contractId,
    milestoneId: detail.milestone.milestoneId,
  }

  async function send() {
    if (complete || Date.now() < resendAt) return false
    return command.run(
      "initiate",
      async (key) => {
        const step = { ...common, step: "initiate" }
        fresh.consumeIntent(action)
        if (!(await fresh.ensureFreshLogin(step))) return
        setChallenge(undefined)
        try {
          const data = await initiateDisputeExit(
            detail,
            intent,
            {
              statementVersion: "1.0",
              presentedContentHash: hash,
            },
            key
          )
          setChallenge(data)
          setResendAt(Date.now() + resendDelay)
        } catch (error) {
          const { code } = parseApiError(error)
          if (code === "OTP_RESEND_TOO_SOON")
            setResendAt(Date.now() + resendDelay)
          if (code === "OTP_RESEND_LIMIT_REACHED")
            setResendAt(Date.now() + 3_600_000)
          await fresh.handleStepUpError(error, step)
          throw error
        }
      }
    )
  }

  async function verify(code: string) {
    if (complete || !challenge) return false
    let verified = false
    await command.run(
      JSON.stringify(["verify", challenge.otpId, code]),
      async (key) => {
        const step = { ...common, step: "verify" }
        if (!(await fresh.ensureFreshLogin(step))) return
        try {
          await verifyDisputeExit(detail, intent, challenge, code, key)
          setChallenge(undefined)
          setComplete(true)
          verified = true
        } catch (error) {
          const parsed = parseApiError(error)
          if (invalidChallenge.has(parsed.code ?? "")) setChallenge(undefined)
          await fresh.handleStepUpError(error, step)
          throw error
        }
      }
    )
    return verified
  }

  return {
    challenge,
    resendAt,
    complete,
    send,
    verify,
    busy: command.busy,
    error: command.error,
  }
}
