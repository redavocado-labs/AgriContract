/**
 * Dev-only mock backend for the BF-04 screens (VITE_MOCK_API=bf04). The only
 * place mock data lives: an axios adapter that answers like contract-service
 * per golden-flow-api.yaml + #552, and a fake signed-in identity.
 *
 * URL switches (kept in sessionStorage across in-app navigation):
 *   ?as=BUYER|SELLER|ARBITRATOR|OPERATOR   who is signed in
 *   ?mock=slow|error|forbidden|notfound|empty|unverified|evidencefail
 */
import {
  AxiosError,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios"
import type { BreachCase } from "@/features/milestones/api/milestones"
import { apiClient } from "@/shared/api/client"
import { useAuthStore, type AppRole } from "@/shared/auth/auth-store"
import {
  consentContent,
  sha256Hex,
} from "@/features/milestones/lib/milestone-consent"
import {
  ARBITRATOR_ID,
  BUYER_ID,
  CONTRACT_ID,
  CONTRACT_2_ID,
  FINISHED_CONTRACT_ID,
  PENDING_CONTRACT_ID,
  disputePositions,
  inspectionRemedy,
  terminalDecision,
  type Detail,
  OPERATOR_ID,
  SELLER_ID,
  contractTerms,
  initialBreachCases,
  initialDetails,
} from "./bf04-fixtures"

const params = new URLSearchParams(window.location.search)
for (const key of ["as", "mock"]) {
  const value = params.get(key)
  if (value !== null) sessionStorage.setItem(`bf04.${key}`, value)
}
const viewer = (sessionStorage.getItem("bf04.as") ?? "SELLER") as AppRole
const mode = sessionStorage.getItem("bf04.mock") ?? ""

const ids: Record<string, string> = {
  BUYER: BUYER_ID,
  SELLER: SELLER_ID,
  ARBITRATOR: ARBITRATOR_ID,
  OPERATOR: OPERATOR_ID,
  ADMIN: "ad000000-0000-4000-8000-000000000001",
  INSPECTOR: "1e000000-0000-4000-8000-000000000001",
}
const orgs: Record<string, string> = {
  BUYER: "Công ty TNHH Cà phê Tây Nguyên",
  SELLER: "HTX Nông nghiệp Ea Kar",
  ARBITRATOR: "Trọng tài AgriContract",
  OPERATOR: "Vận hành AgriContract",
}

const persisted = window.sessionStorage.getItem(`bf04.state.d99.${mode}`)
const saved = persisted
  ? (JSON.parse(persisted) as { details: Detail[]; breachCases: BreachCase[] })
  : null
const details = saved?.details ?? initialDetails()
const breachCases = saved?.breachCases ?? initialBreachCases()
const challenges = new Map<
  string,
  {
    action: "concede" | "settlement"
    milestoneId: string
    amount?: string
    proposalAt?: string
    expiresAt: string
  }
>()
const writes = new Map<string, { body: string; reply: Reply }>()
const contestEvidence = new Map<string, Set<string>>()
const contractIds = [
  CONTRACT_ID,
  CONTRACT_2_ID,
  PENDING_CONTRACT_ID,
  FINISHED_CONTRACT_ID,
]
const signedContentHash =
  "3f9a1c2e7b4d8f60a5c3e1d9b7f2a4c6e8d0b2f4a6c8e0d2b4f6a8c0e2d4b6f8"
const reads = new Map<string, number>()

function envelope(status: number, code: string, message: string, path: string) {
  return {
    timestamp: new Date().toISOString(),
    status,
    error:
      status === 403
        ? "Forbidden"
        : status === 404
          ? "Not Found"
          : status === 409
            ? "Conflict"
            : "Error",
    code,
    message,
    path,
    traceId: "mock-trace",
  }
}
class Reply {
  readonly status: number
  readonly data?: unknown
  constructor(status: number, data?: unknown) {
    this.status = status
    this.data = data
  }
}
const fail = (status: number, code: string, path: string) =>
  new Reply(status, envelope(status, code, `${code} (mock)`, path))

function contractResponse(contractId: string) {
  const milestones = details
    .filter((d) => d.contractId === contractId)
    .map((d) => d.milestone)
  const complete = contractId === FINISHED_CONTRACT_ID
  const pending =
    contractId === PENDING_CONTRACT_ID ||
    (!complete &&
      milestones.length > 0 &&
      milestones.every((item) => item.status === "SETTLED"))
  const terms = contractTerms()
  return {
    contractId,
    listingId: "1157e000-0000-4000-8000-000000000001",
    reservedQuantity: milestones.reduce(
      (sum, item) => sum + item.committedQuantity,
      0
    ),
    buyerId: BUYER_ID,
    sellerId: SELLER_ID,
    status: complete ? "SETTLED" : "ACTIVE",
    terms:
      mode === "mandatory"
        ? {
            ...terms,
            inspectionTerms: {
                requirement: "MANDATORY_BEFORE_SETTLEMENT",
            },
          }
        : terms,
    signedContentHash,
    milestones,
    ...(complete || pending
      ? { terminalDecision: terminalDecision(complete) }
      : {}),
  }
}

const validEvidence = (detail: Detail, ids: string[], role: AppRole) =>
  ids.every((id) =>
    detail.evidence.some(
      (file) =>
        file.fileId === id &&
        file.status === "READY" &&
        file.uploadedByRole === role
    )
  )

function newBreach(detail: Detail): BreachCase {
  return {
    breachCaseId: crypto.randomUUID(),
    contractId: detail.contractId,
    milestoneId: detail.milestone.milestoneId,
    requestedBy: "BUYER",
    allegedBreachingRole: "SELLER",
    severity: "MINOR",
    breachReasonCode:
      detail.flag?.issueType === "QUALITY"
        ? "QUALITY_BELOW_COMMITTED"
        : "SHORTFALL",
    evidenceFileIds: detail.buyerReceipt
      ? [detail.buyerReceipt.buyerEvidenceFileId]
      : [],
    status: "REPORTED",
    computedResolution: null,
    finalBreachingRole: null,
    decisionSource: null,
    reportedAt: new Date().toISOString(),
    resolvedAt: null,
    contestStatus: "NOT_APPLICABLE",
    contestDeadline: null,
  }
}

function settleInspection(
  detail: Detail,
  resolution: NonNullable<BreachCase["computedResolution"]>
) {
  detail.milestone.status = "SETTLED"
  detail.settlement = {
    path: "DISPUTE_RESOLVED",
    billableQuantityKg: resolution.acceptedQuantityKg,
    sellerAmount: resolution.sellerEntitlementAmount,
    buyerRefundAmount: resolution.buyerRefundAmount,
    settledAt: new Date().toISOString(),
  }
}

/** Placeholder "photo" of a weighbridge display, so screens show a believable image. */
function scalePhoto(weight: number | undefined, role: string) {
  const reading =
    weight == null ? "ẢNH CÂN" : `${weight.toLocaleString("vi-VN")} kg`
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="480" viewBox="0 0 640 480">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#cbd5e1"/><stop offset="1" stop-color="#64748b"/></linearGradient></defs>
<rect width="640" height="480" fill="url(#g)"/>
<rect x="40" y="300" width="560" height="140" rx="10" fill="#475569"/>
<rect x="80" y="250" width="200" height="80" rx="6" fill="#92400e" opacity=".85"/>
<rect x="300" y="230" width="240" height="100" rx="6" fill="#78350f" opacity=".8"/>
<rect x="150" y="60" width="340" height="130" rx="14" fill="#0f172a"/>
<text x="320" y="145" font-family="monospace" font-size="44" fill="#4ade80" text-anchor="middle">${reading}</text>
<text x="320" y="180" font-family="sans-serif" font-size="16" fill="#94a3b8" text-anchor="middle">${role === "SELLER" ? "Cân xuất kho" : "Cân nhận hàng"}</text>
</svg>`
  return new Blob([svg], { type: "image/svg+xml" })
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

function findDetail(contractId: string, milestoneId: string) {
  return details.find(
    (d) =>
      d.contractId === contractId && d.milestone.milestoneId === milestoneId
  )
}

async function expectHash(
  body: {
    consent?: { presentedContentHash?: string }
    presentedContentHash?: string
  },
  parts: Parameters<typeof consentContent>[0]
) {
  const presented =
    body.consent?.presentedContentHash ?? body.presentedContentHash
  return presented === (await sha256Hex(consentContent(parts)))
}

async function route(
  method: string,
  path: string,
  config: InternalAxiosRequestConfig
): Promise<Reply> {
  const body =
    typeof config.data === "string" ? JSON.parse(config.data) : config.data
  const party = viewer === "BUYER" || viewer === "SELLER"
  if (mode === "slow" && method === "get" && !path.endsWith("/users/me"))
    await sleep(120_000)
  else await sleep(250)
  if (method === "get" && path === "/api/v1/users/me")
    return new Reply(200, {
      userId: ids[viewer],
      organizationName: orgs[viewer],
      role: viewer,
      email: `${viewer.toLowerCase()}@example.test`,
      emailVerified: true,
      phone: null,
      address: "Buôn Ma Thuột, Đắk Lắk",
      verificationStatus: mode === "unverified" ? "PENDING" : "VERIFIED",
      authorizationExpiresAt: null,
    })
  if (mode === "error" && method === "get")
    return fail(503, "SERVICE_UNAVAILABLE", path)
  if (mode === "forbidden" && method === "get")
    return fail(403, "FORBIDDEN", path)
  if (mode === "notfound" && method === "get" && path.includes("/milestones/"))
    return fail(404, "MILESTONE_NOT_FOUND", path)

  let m: RegExpMatchArray | null
  if (method === "get" && path === "/api/v1/contracts")
    return new Reply(200, {
      content:
        party && mode !== "empty" ? contractIds.map(contractResponse) : [],
      page: 0,
      size: 20,
      totalElements: party && mode !== "empty" ? contractIds.length : 0,
      totalPages: party && mode !== "empty" ? 1 : 0,
    })
  if (method === "get" && (m = path.match(/^\/api\/v1\/contracts\/([^/]+)$/))) {
    if (!party) return fail(403, "FORBIDDEN", path)
    const id = decodeURIComponent(m[1])
    return contractIds.includes(id)
      ? new Reply(200, contractResponse(id))
      : fail(404, "CONTRACT_NOT_FOUND", path)
  }
  if (method === "get" && path === "/api/v1/arbitration/breach-cases") {
    if (viewer !== "ARBITRATOR") return fail(403, "FORBIDDEN", path)
    const filtered =
      mode === "empty"
        ? []
        : breachCases.filter(
            (b) => b.status !== "RESOLVED" && b.computedResolution
          )
    const page = Number(config.params?.page ?? 0)
    const size = Number(config.params?.size ?? 20)
    const content = filtered.slice(page * size, (page + 1) * size)
    return new Reply(200, {
      content,
      page,
      size,
      totalElements: filtered.length,
      totalPages: Math.ceil(filtered.length / size),
    })
  }
  if (
    (m = path.match(
      /^\/api\/v1\/contracts\/([^/]+)\/breach-cases\/([^/]+)(\/review|\/resolve|\/contest(?:\/evidence)?)?$/
    ))
  ) {
    const breach = breachCases.find(
      (b) =>
        b.contractId === decodeURIComponent(m![1]) &&
        b.breachCaseId === decodeURIComponent(m![2])
    )
    if (!breach) return fail(404, "BREACH_CASE_NOT_FOUND", path)
    if (method === "get")
      return party || viewer === "ARBITRATOR"
        ? new Reply(200, breach)
        : fail(403, "FORBIDDEN", path)
    const detail = details.find((d) => d.breachCaseId === breach.breachCaseId)
    if (method === "post" && m[3]?.startsWith("/contest")) {
      if (!party || breach.finalBreachingRole !== viewer)
        return fail(403, "FORBIDDEN", path)
      const ids: string[] =
        m[3] === "/contest"
          ? (body.evidenceFileIds ?? [])
          : (body.fileIds ?? [])
      if (
        !detail ||
        detail.milestone.status !== "CONTESTED" ||
        breach.status !== "RESOLVED" ||
        (m[3] === "/contest"
          ? breach.contestStatus !== "OPEN" ||
            !breach.contestDeadline ||
            Date.parse(breach.contestDeadline) <= Date.now()
          : breach.contestStatus !== "CONTESTED")
      )
        return fail(409, "BREACH_CASE_ACTION_NOT_ALLOWED", path)
      if (
        ids.length > 10 ||
        (m[3] === "/contest" ? !body.reason?.trim() : !ids.length)
      )
        return fail(400, "VALIDATION_ERROR", path)
      if (!validEvidence(detail, ids, viewer))
        return fail(409, "EVIDENCE_NOT_READY", path)
      if (m[3] === "/contest") {
        breach.contestStatus = "CONTESTED"
        detail.disputeExit = disputePositions(true)
      }
      const evidence = new Set([
        ...(contestEvidence.get(breach.breachCaseId) ?? []),
        ...ids,
      ])
      if (evidence.size > 30) return fail(400, "VALIDATION_ERROR", path)
      contestEvidence.set(breach.breachCaseId, evidence)
      return new Reply(204)
    }
    if (viewer !== "ARBITRATOR") return fail(403, "FORBIDDEN", path)
    if (method !== "post") return fail(404, "NOT_FOUND", path)
    if (m[3] === "/review" && breach.status === "REPORTED") {
      breach.status = "UNDER_REVIEW"
      return new Reply(204)
    }
    if (m[3] !== "/resolve" || breach.status !== "UNDER_REVIEW")
      return fail(409, "BREACH_CASE_ACTION_NOT_ALLOWED", path)
    const resolution = breach.computedResolution
    if (!resolution) return fail(409, "INSPECTION_REPORT_PENDING", path)
    if (
      body.finalBreachingRole !== resolution.finalBreachingRole ||
      body.decisionSource !== "INSPECTOR_L1_5"
    )
      return fail(409, "RESOLUTION_MISMATCH", path)
    breach.status = "RESOLVED"
    breach.resolvedAt = new Date().toISOString()
    breach.decisionSource = "INSPECTOR_L1_5"
    breach.finalBreachingRole = resolution.finalBreachingRole
    if (detail) {
      const second = breach.contestStatus === "CONTESTED"
      const open = resolution.qualityDisposition === "NON_CONFORMING" && !second
      if (open) {
        breach.contestStatus = "OPEN"
        breach.contestDeadline = new Date(
          Date.now() + 2 * 86400000
        ).toISOString()
      } else {
        settleInspection(detail, resolution)
      }
      detail.disputeExit = null
      detail.remedyDecision = inspectionRemedy(
        open ? "PENDING_CONTEST" : "FINALIZED",
        resolution.sellerEntitlementAmount,
        resolution.buyerRefundAmount,
        !!resolution.finalBreachingRole
      )
    }
    return new Reply(204)
  }

  const remedy = path.match(
    /^\/api\/v1\/contracts\/([^/]+)\/remedy\/retry-legs$/
  )
  if (method === "post" && remedy) {
    if (viewer !== "OPERATOR") return fail(403, "FORBIDDEN", path)
    if (!contractIds.includes(remedy[1]))
      return fail(404, "CONTRACT_NOT_FOUND", path)
    return remedy[1] === FINISHED_CONTRACT_ID
      ? fail(409, "REMEDY_RETRY_NOT_ALLOWED", path)
      : new Reply(204)
  }

  m = path.match(
    /^\/api\/v1\/contracts\/([^/]+)\/milestones\/([^/]+)(?:\/(.+))?$/
  )
  if (!m) return fail(404, "NOT_FOUND", path)
  const detail = findDetail(decodeURIComponent(m[1]), decodeURIComponent(m[2]))
  if (!detail) return fail(404, "MILESTONE_NOT_FOUND", path)
  const action = m[3]
  const ms = detail.milestone
  const nowIso = () => new Date().toISOString()
  if (!party && viewer !== "ARBITRATOR" && viewer !== "OPERATOR")
    return fail(403, "FORBIDDEN", path)
  if (method === "get" && !action) {
    if (mode === "expiredcontest" && detail.breachCaseId) {
      const existing = breachCases.find(
        (item) => item.breachCaseId === detail.breachCaseId
      )
      if (existing?.contestStatus === "OPEN") {
        existing.contestStatus = "EXPIRED"
        existing.contestDeadline = new Date(Date.now() - 60_000).toISOString()
      }
    }
    for (const file of detail.evidence)
      if (file.status === "PROCESSING") {
        const count = (reads.get(file.fileId) ?? 0) + 1
        reads.set(file.fileId, count)
        if (count >= 2)
          file.status = mode === "evidencefail" ? "FAILED" : "READY"
      }
    return new Reply(200, detail)
  }
  if (method === "get" && action?.startsWith("evidence/")) {
    if (viewer === "OPERATOR") return fail(403, "FORBIDDEN", path)
    const fileId = decodeURIComponent(action.slice("evidence/".length))
    const owner = detail.evidence.find((f) => f.fileId === fileId)
    if (!owner) return fail(404, "EVIDENCE_NOT_FOUND", path)
    if (owner.status !== "READY")
      return fail(
        409,
        owner.status === "FAILED" ? "EVIDENCE_REJECTED" : "EVIDENCE_NOT_READY",
        path
      )
    const weight =
      owner.uploadedByRole === "SELLER"
        ? detail.sellerDeclaration?.sellerDeclaredWeight
        : detail.buyerReceipt?.buyerReceivedWeight
    return new Reply(200, scalePhoto(weight, owner.uploadedByRole))
  }
  const role = viewer
  const notAllowed = () => fail(409, "MILESTONE_ACTION_NOT_ALLOWED", path)
  const mismatch = () => fail(409, "CONSENT_CONTENT_MISMATCH", path)
  if (action?.startsWith("dispute/"))
    return disputeCommand(method, action, detail, body, path)
  if (method !== "post") return fail(404, "NOT_FOUND", path)
  if (
    ["weigh", "receive", "confirm", "flag", "respond"].includes(action ?? "") &&
    (body?.consent?.statementVersion ?? body?.statementVersion) !==
      (action === "flag" ? "1.1" : "1.0")
  )
    return fail(409, "CONSENT_STATEMENT_OUTDATED", path)
  switch (action) {
    case "evidence": {
      if (role !== "BUYER" && role !== "SELLER")
        return fail(403, "FORBIDDEN", path)
      const until = detail.sellerDeclaration?.correctionWindowClosesAt
      const openCorrection = !!until && Date.parse(until) > Date.now()
      const breach = breachCases.find(
        (item) => item.breachCaseId === detail.breachCaseId
      )
      const contest =
        ms.status === "CONTESTED" &&
        breach?.status === "RESOLVED" &&
        breach.finalBreachingRole === role &&
        (breach.contestStatus === "CONTESTED" ||
          (breach.contestStatus === "OPEN" &&
            !!breach.contestDeadline &&
            Date.parse(breach.contestDeadline) > Date.now()))
      if (
        !contest &&
        !(ms.status === "IN_PROGRESS" && role === "SELLER") &&
        !(
          ms.status === "SELLER_WEIGHED" &&
          (role === "BUYER" || openCorrection)
        )
      )
        return notAllowed()
      const fileId = crypto.randomUUID()
      detail.evidence.push({
        fileId,
        uploadedByRole: role,
        status: "PROCESSING",
        uploadedAt: nowIso(),
      })
      return new Reply(202, { fileId, status: "PROCESSING" })
    }
    case "weigh": {
      if (role !== "SELLER") return fail(403, "FORBIDDEN", path)
      if (
        !validEvidence(
          detail,
          [
            body.sellerEvidenceFileId,
            ...(body.deliveryCertificateFileId
              ? [body.deliveryCertificateFileId]
              : []),
          ],
          role
        )
      )
        return fail(409, "EVIDENCE_NOT_READY", path)
      const correcting = ms.status === "SELLER_WEIGHED"
      if (ms.status !== "IN_PROGRESS" && !correcting) return notAllowed()
      if (
        correcting &&
        Date.parse(detail.sellerDeclaration!.correctionWindowClosesAt) <
          Date.now()
      )
        return fail(409, "WEIGH_CORRECTION_CLOSED", path)
      if (
        !(await expectHash(body, {
          action: "DECLARE_SELLER_WEIGHT",
          milestoneId: ms.milestoneId,
          sellerDeclaredWeight: body.sellerDeclaredWeight,
          sellerEvidenceFileId: body.sellerEvidenceFileId,
          deliveryCertificateFileId: body.deliveryCertificateFileId ?? null,
        }))
      )
        return mismatch()
      const first = detail.sellerDeclaration
      detail.sellerDeclaration = {
        declarationNumber: (first?.declarationNumber ?? 0) + 1,
        sellerDeclaredWeight: body.sellerDeclaredWeight,
        sellerEvidenceFileId: body.sellerEvidenceFileId,
        deliveryCertificateFileId: body.deliveryCertificateFileId ?? null,
        declaredAt: nowIso(),
        correctionWindowClosesAt:
          first?.correctionWindowClosesAt ??
          new Date(Date.now() + 3_600_000).toISOString(),
      }
      ms.status = "SELLER_WEIGHED"
      return new Reply(204)
    }
    case "receive": {
      if (role !== "BUYER") return fail(403, "FORBIDDEN", path)
      if (ms.status !== "SELLER_WEIGHED") return notAllowed()
      if (
        !validEvidence(
          detail,
          [
            body.buyerEvidenceFileId,
            ...(body.deliveryCertificateFileId
              ? [body.deliveryCertificateFileId]
              : []),
          ],
          role
        )
      )
        return fail(409, "EVIDENCE_NOT_READY", path)
      if (
        !(await expectHash(body, {
          action: "RECORD_BUYER_RECEIPT",
          milestoneId: ms.milestoneId,
          buyerReceivedWeight: body.buyerReceivedWeight,
          buyerEvidenceFileId: body.buyerEvidenceFileId,
          deliveryCertificateFileId: body.deliveryCertificateFileId ?? null,
        }))
      )
        return mismatch()
      detail.buyerReceipt = {
        buyerReceivedWeight: body.buyerReceivedWeight,
        buyerEvidenceFileId: body.buyerEvidenceFileId,
        deliveryCertificateFileId: body.deliveryCertificateFileId ?? null,
        receivedAt: nowIso(),
        confirmWindowClosesAt: new Date(
          Date.now() + 3 * 86_400_000
        ).toISOString(),
      }
      ms.status = "BUYER_RECEIVED"
      return new Reply(204)
    }
    case "confirm":
    case "flag": {
      if (role !== "BUYER") return fail(403, "FORBIDDEN", path)
      if (ms.status !== "BUYER_RECEIVED") return notAllowed()
      const d = detail.sellerDeclaration!
      const r = detail.buyerReceipt!
      if (Date.parse(r.confirmWindowClosesAt) <= Date.now()) return notAllowed()
      const common = {
        milestoneId: ms.milestoneId,
        declarationNumber: d.declarationNumber,
        sellerDeclaredWeight: d.sellerDeclaredWeight,
        buyerReceivedWeight: r.buyerReceivedWeight,
      }
      if (action === "confirm") {
        if (mode === "mandatory")
          return fail(409, "INSPECTION_REPORT_PENDING", path)
        if (
          !(await expectHash(body, {
            action: "CONFIRM_CLEAN_RECEIPT",
            ...common,
          }))
        )
          return mismatch()
        const tolerance = contractTerms().toleranceRate * d.sellerDeclaredWeight
        const loss = Math.max(0, d.sellerDeclaredWeight - r.buyerReceivedWeight)
        const billable =
          loss <= tolerance
            ? d.sellerDeclaredWeight
            : d.sellerDeclaredWeight - (loss - tolerance) / 2
        const seller = Math.min(950_000_000, Math.round(billable * 95_000))
        ms.status = "SETTLED"
        detail.settlement = {
          path: "CLEAN_CONFIRMED",
          billableQuantityKg: Math.round(billable * 1000) / 1000,
          sellerAmount: `${seller}.00`,
          buyerRefundAmount: `${950_000_000 - seller}.00`,
          settledAt: nowIso(),
        }
        return new Reply(204)
      }
      if (
        !(await expectHash(body, {
          action: "FLAG_MILESTONE_ISSUE",
          ...common,
          issueType: body.issueType,
        }))
      )
        return mismatch()
      detail.flag = {
        issueType: body.issueType,
        flaggedAt: nowIso(),
        sellerResponseWindowClosesAt: new Date(
          Date.now() + 2 * 86_400_000
        ).toISOString(),
        contestedAt: null,
        contestedBy: null,
      }
      detail.disputeExit = disputePositions(false)
      ms.status = "AWAITING_SELLER_RESPONSE"
      return new Reply(204)
    }
    case "respond": {
      if (role !== "SELLER") return fail(403, "FORBIDDEN", path)
      if (
        ms.status !== "AWAITING_SELLER_RESPONSE" ||
        !detail.flag ||
        Date.parse(detail.flag.sellerResponseWindowClosesAt) <= Date.now()
      )
        return notAllowed()
      const d = detail.sellerDeclaration!
      const r = detail.buyerReceipt!
      if (
        !(await expectHash(body, {
          action: "CONTEST_MILESTONE_FLAG",
          milestoneId: ms.milestoneId,
          sellerDeclaredWeight: d.sellerDeclaredWeight,
          buyerReceivedWeight: r.buyerReceivedWeight,
          issueType: detail.flag!.issueType,
        }))
      )
        return mismatch()
      detail.flag = {
        ...detail.flag!,
        contestedAt: nowIso(),
        contestedBy: "SELLER",
      }
      ms.status = "CONTESTED"
      const breach = newBreach(detail)
      detail.breachCaseId = breach.breachCaseId
      breachCases.push(breach)
      detail.disputeExit = disputePositions(true)
      return new Reply(204)
    }
  }
  return fail(404, "NOT_FOUND", path)
}

function disputeCommand(
  method: string,
  action: string,
  detail: Detail,
  body:
    | {
        otpId?: string
        otpCode?: string
        consentStatementVersion?: string
        presentedContentHash?: string
        agreedSellerEntitlementAmount?: string
      }
    | undefined,
  path: string
): Reply {
  if (viewer !== "BUYER" && viewer !== "SELLER")
    return fail(403, "FORBIDDEN", path)
  const match = action.match(
    /^dispute\/(concede|settlement)(?:\/(initiate|verify))?$/
  )
  if (!match) return fail(404, "NOT_FOUND", path)
  const kind = match[1] === "concede" ? "concede" : "settlement"
  const exit = detail.disputeExit
  if (
    !exit ||
    !(kind === "concede" ? exit.concedeAllowed : exit.settlementAllowed)
  )
    return fail(409, "DISPUTE_EXIT_NOT_ALLOWED", path)
  const proposal = exit.openProposal
  if (kind === "settlement" && method === "delete" && !match[2]) {
    if (!proposal) return fail(409, "SETTLEMENT_PROPOSAL_CONFLICT", path)
    exit.openProposal = null
    return new Reply(204)
  }
  if (method !== "post" || !body) return fail(404, "NOT_FOUND", path)
  if (match[2] === "initiate") {
    if (body.consentStatementVersion !== "1.0")
      return fail(409, "CONSENT_STATEMENT_OUTDATED", path)
    if (body.presentedContentHash !== signedContentHash)
      return fail(409, "PRESENTED_CONTENT_HASH_MISMATCH", path)
    const amount = body.agreedSellerEntitlementAmount
    if (kind === "settlement") {
      if (
        !amount ||
        !/^\d+\.00$/.test(amount) ||
        Number(amount) < Number(exit.buyerPositionAmount) ||
        Number(amount) > Number(exit.sellerPositionAmount)
      )
        return fail(400, "VALIDATION_ERROR", path)
      if (
        proposal &&
        (proposal.proposedByRole === viewer ||
          proposal.agreedSellerEntitlementAmount !== amount)
      )
        return fail(409, "SETTLEMENT_PROPOSAL_CONFLICT", path)
    }
    const otpId = crypto.randomUUID()
    const expiresAt = new Date(Date.now() + 5 * 60_000).toISOString()
    for (const [id, challenge] of challenges)
      if (
        challenge.action === kind &&
        challenge.milestoneId === detail.milestone.milestoneId
      )
        challenges.delete(id)
    challenges.set(otpId, {
      action: kind,
      milestoneId: detail.milestone.milestoneId,
      amount,
      proposalAt: proposal?.proposedAt,
      expiresAt,
    })
    return new Reply(200, { otpId, expiresAt })
  }

  if (match[2] !== "verify" || !body.otpId) return fail(404, "NOT_FOUND", path)
  const challenge = challenges.get(body.otpId)
  if (
    !challenge ||
    challenge.action !== kind ||
    challenge.milestoneId !== detail.milestone.milestoneId
  )
    return fail(409, "OTP_CHALLENGE_NOT_FOUND", path)
  if (Date.parse(challenge.expiresAt) <= Date.now())
    return fail(409, "OTP_EXPIRED", path)
  if (body.otpCode !== "123456") return fail(409, "OTP_INVALID", path)
  if (kind === "settlement" && challenge.proposalAt !== proposal?.proposedAt)
    return fail(409, "SETTLEMENT_PROPOSAL_CONFLICT", path)
  const now = new Date().toISOString()
  const buyerPosition = viewer === "SELLER"
  const seller =
    kind === "concede"
      ? buyerPosition
        ? exit.buyerPositionAmount
        : exit.sellerPositionAmount
      : challenge.amount!
  const refund =
    kind === "concede"
      ? buyerPosition
        ? exit.buyerPositionRefundAmount!
        : exit.sellerPositionRefundAmount!
      : (proposal?.agreedBuyerRefundAmount ??
        `${Number(detail.milestone.batchAmount) - Number(seller)}.00`)
  challenges.delete(body.otpId)
  if (kind === "settlement" && !proposal) {
    exit.openProposal = {
      proposedByRole: viewer,
      proposedAt: now,
      agreedSellerEntitlementAmount: seller,
      agreedBuyerRefundAmount: refund,
    }
    return new Reply(204)
  }
  let breach = breachCases.find(
    (item) => item.breachCaseId === detail.breachCaseId
  )
  if (!breach) {
    breach = newBreach(detail)
    detail.breachCaseId = breach.breachCaseId
    breachCases.push(breach)
  }
  const blamed =
    kind === "concede" &&
    buyerPosition &&
    (detail.flag?.issueType === "QUALITY" ||
      breach.contestStatus === "CONTESTED")
  breach.status = "RESOLVED"
  breach.decisionSource = "MUTUAL"
  breach.resolvedAt = now
  breach.finalBreachingRole = blamed ? "SELLER" : null
  breach.computedResolution = null
  detail.disputeExit = null
  detail.milestone.status = "SETTLED"
  detail.settlement = {
    path: "DISPUTE_RESOLVED",
    billableQuantityKg:
      Math.round(
        (Number(seller) / Number(detail.milestone.effectiveUnitPrice)) * 1000
      ) / 1000,
    sellerAmount: seller,
    buyerRefundAmount: refund,
    settledAt: now,
  }
  detail.remedyDecision = {
    ...inspectionRemedy("FINALIZED", seller, refund, blamed),
    decisionSource: "MUTUAL",
  }
  return new Reply(204)
}

const adapter: AxiosAdapter = async (config) => {
  const url = new URL(config.url ?? "", "http://mock")
  const method = (config.method ?? "get").toLowerCase()
  const key = config.headers.get("Idempotency-Key")
  const body =
    config.data instanceof File
      ? [config.data.name, config.data.type, config.data.size].join("|")
      : String(config.data ?? "")
  const write =
    key && method !== "get"
      ? `${viewer}:${method}:${url.pathname}:${key}`
      : null
  const prior = write ? writes.get(write) : undefined
  const reply = prior
    ? prior.body === body
      ? prior.reply
      : fail(409, "IDEMPOTENCY_KEY_REUSED", url.pathname)
    : await route(method, url.pathname, config)
  if (write && !prior && reply.status < 300) {
    writes.set(write, { body, reply })
    sessionStorage.setItem(
      `bf04.state.d99.${mode}`,
      JSON.stringify({ details, breachCases })
    )
  }
  const response: AxiosResponse = {
    data: reply.data,
    status: reply.status,
    statusText: String(reply.status),
    headers: {},
    config,
    request: {},
  }
  if (reply.status >= 400)
    throw new AxiosError(
      `Mock ${reply.status}`,
      "ERR_BAD_REQUEST",
      config,
      {},
      response
    )
  return response
}

export function installBf04Mock() {
  apiClient.defaults.adapter = adapter
  useAuthStore.setState({
    authenticated: true,
    token: "mock-token",
    userId: ids[viewer],
    email: `${viewer.toLowerCase()}@example.test`,
    roles: [viewer],
    authTime: Math.floor(Date.now() / 1000),
    status: "ready",
    error: undefined,
  })
}
