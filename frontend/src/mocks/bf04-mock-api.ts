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
}
const orgs: Record<string, string> = {
  BUYER: "Công ty TNHH Cà phê Tây Nguyên",
  SELLER: "HTX Nông nghiệp Ea Kar",
  ARBITRATOR: "Trọng tài AgriContract",
  OPERATOR: "Vận hành AgriContract",
}

const details = initialDetails()
const breachCases = initialBreachCases()
const reads = new Map<string, number>()
let fileSeq = 50

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
  return {
    contractId,
    listingId: "1157e000-0000-4000-8000-000000000001",
    reservedQuantity: 90000,
    buyerId: BUYER_ID,
    sellerId: SELLER_ID,
    status: "ACTIVE",
    terms: contractTerms(),
    signedContentHash:
      "3f9a1c2e7b4d8f60a5c3e1d9b7f2a4c6e8d0b2f4a6c8e0d2b4f6a8c0e2d4b6f8",
    milestones: details
      .filter((d) => d.contractId === contractId)
      .map((d) => d.milestone),
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
      content: [contractResponse(CONTRACT_ID)],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
  if (method === "get" && (m = path.match(/^\/api\/v1\/contracts\/([^/]+)$/))) {
    if (!party) return fail(403, "FORBIDDEN", path)
    return new Reply(200, contractResponse(decodeURIComponent(m[1])))
  }
  if (method === "get" && path === "/api/v1/arbitration/breach-cases") {
    if (viewer !== "ARBITRATOR") return fail(403, "FORBIDDEN", path)
    const content =
      mode === "empty"
        ? []
        : breachCases.filter(
            (b) => b.status !== "RESOLVED" && b.computedResolution
          )
    return new Reply(200, {
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length ? 1 : 0,
    })
  }
  if (
    (m = path.match(
      /^\/api\/v1\/contracts\/([^/]+)\/breach-cases\/([^/]+)(\/review|\/resolve)?$/
    ))
  ) {
    const breach = breachCases.find(
      (b) => b.breachCaseId === decodeURIComponent(m![2])
    )
    if (!breach) return fail(404, "BREACH_CASE_NOT_FOUND", path)
    if (method === "get") return new Reply(200, breach)
    if (viewer !== "ARBITRATOR") return fail(403, "FORBIDDEN", path)
    if (m[3] === "/review") {
      breach.status = "UNDER_REVIEW"
      return new Reply(204)
    }
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
    const detail = details.find((d) => d.breachCaseId === breach.breachCaseId)
    if (detail && !resolution.finalBreachingRole) {
      detail.milestone.status = "SETTLED"
      detail.settlement = {
        path: "INSPECTION_RESOLVED",
        billableQuantityKg: resolution.acceptedQuantityKg,
        sellerAmount: resolution.sellerEntitlementAmount,
        buyerRefundAmount: resolution.buyerRefundAmount,
        settledAt: new Date().toISOString(),
      }
    }
    return new Reply(204)
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
  if (method === "get" && !action) {
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
  switch (action) {
    case "evidence": {
      if (role !== "BUYER" && role !== "SELLER")
        return fail(403, "FORBIDDEN", path)
      const fileId = `f11e0000-0000-4000-8000-0000000001${String(fileSeq++).padStart(2, "0")}`
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
      const common = {
        milestoneId: ms.milestoneId,
        declarationNumber: d.declarationNumber,
        sellerDeclaredWeight: d.sellerDeclaredWeight,
        buyerReceivedWeight: r.buyerReceivedWeight,
      }
      if (action === "confirm") {
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
      ms.status = "AWAITING_SELLER_RESPONSE"
      return new Reply(204)
    }
    case "respond": {
      if (role !== "SELLER") return fail(403, "FORBIDDEN", path)
      if (ms.status !== "AWAITING_SELLER_RESPONSE") return notAllowed()
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
      return new Reply(204)
    }
    case "funding/retry": {
      if (role !== "OPERATOR") return fail(403, "FORBIDDEN", path)
      if (ms.fundingStatus !== "FUNDING_FAILED") return notAllowed()
      ms.fundingStatus = "FUNDING_PENDING"
      return new Reply(204)
    }
  }
  return fail(404, "NOT_FOUND", path)
}

const adapter: AxiosAdapter = async (config) => {
  const url = new URL(config.url ?? "", "http://mock")
  const reply = await route(
    (config.method ?? "get").toLowerCase(),
    url.pathname,
    config
  )
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
