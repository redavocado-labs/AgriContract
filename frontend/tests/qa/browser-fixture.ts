import assert from "node:assert/strict"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { test as base, type Page, type Request, type Route } from "@playwright/test"
import { createServer } from "vite"

export const contractId = "00000000-0000-4000-8000-000000000001"
export const otherContractId = "00000000-0000-4000-8000-000000000002"
export const sellerId = "00000000-0000-4000-8000-000000000003"
export const buyerId = "00000000-0000-4000-8000-000000000004"
export const milestoneId = "00000000-0000-4000-8000-000000000005"
export const fileId = "00000000-0000-4000-8000-000000000006"
export const secondFileId = "00000000-0000-4000-8000-000000000007"
export const contractPath = `/api/v1/contracts/${contractId}`
export const milestonePath = `${contractPath}/milestones/${milestoneId}`
export const now = new Date("2026-11-24T08:00:00Z")

export function contractDto(status: string, id = contractId) {
  return {
    contractId: id,
    listingId: "00000000-0000-4000-8000-000000000008",
    buyerId,
    sellerId,
    status,
    terms: null,
    signedContentHash: null,
    milestones: [],
    reservedQuantity: 2000,
  }
}

export function milestoneDto(evidence: ReturnType<typeof evidenceDto>[] = []) {
  return {
    contractId,
    milestone: {
      milestoneId,
      milestoneIndex: 1,
      status: "IN_PROGRESS",
      fundingStatus: "LOCKED",
      fundingScheduledDate: "2026-11-20",
      committedQuantity: 2000,
      batchAmount: "200000000.00",
      effectiveUnitPrice: "100000.00",
      acceptedPriceAdjustment: null,
      expectedDeliveryDate: "2026-11-30",
      graceDays: 1,
      fundingDelayBusinessDays: 0,
      effectiveDeliveryDeadline: "2026-12-01",
    },
    evidence,
    sellerDeclaration: null,
    buyerReceipt: null,
    flag: null,
    settlement: null,
    breachCaseId: null,
  }
}

export function evidenceDto(id: string, status: "PROCESSING" | "READY" | "FAILED") {
  return {
    fileId: id,
    uploadedByRole: "SELLER",
    uploadedAt: now.toISOString(),
    status,
  }
}

export const json = (status: number, body: unknown) => ({ status, json: body })

export const errorDto = (status: number, code: string) =>
  json(status, { status, code, message: code })

const identityModule = `
  import { useAuthStore } from "/src/shared/auth/auth-store.ts"
  import { queryClient } from "/src/shared/api/query-client.ts"
  const actor = () => localStorage.getItem("qa-actor") ?? "SELLER"
  const identity = () => actor() === "GUEST" ? {
    authenticated: false,
    roles: [],
    status: "ready",
  } : ({
    authenticated: true,
    userId: actor() === "BUYER" ? "${buyerId}" : "${sellerId}",
    roles: [actor()],
    status: "ready",
    authTime: Math.floor(Date.now() / 1000),
  })
  export const keycloak = { authenticated: actor() !== "GUEST" }
  export const initializeAuth = async () => useAuthStore.setState(identity())
  export const login = async () => { window.qaLoginCount = (window.qaLoginCount ?? 0) + 1 }
  export const register = login
  export const logout = async () => {
    queryClient.clear()
    useAuthStore.setState({ authenticated: false, roles: [], userId: undefined, status: "ready" })
  }
  export const refreshAccessToken = async () => "qa-" + actor()
  export const safeRedirectUri = (value = location.href) => value
`

async function startFrontend() {
  process.env.VITE_API_BASE_URL = "/"
  process.env.VITE_MOCK_API = ""
  const root = process.env.QA_FRONTEND_ROOT ??
    fileURLToPath(new URL("../../", import.meta.url))
  const cacheDir = await mkdtemp(join(tmpdir(), "agricontract-qa-vite-"))
  try {
    const server = await createServer({
      root,
      cacheDir,
      server: {
        host: "127.0.0.1",
        port: 0,
        hmr: false,
        fs: {
          allow: [root, fileURLToPath(new URL("../../node_modules/", import.meta.url))],
        },
      },
      logLevel: "error",
    })
    try {
      await server.listen()
      const address = server.httpServer?.address()
      assert(address && typeof address === "object")
      return {
        origin: `http://127.0.0.1:${address.port}`,
        async close() {
          try {
            await server.close()
          } finally {
            await rm(cacheDir, { recursive: true, force: true })
          }
        },
      }
    } catch (error) {
      await server.close()
      throw error
    }
  } catch (error) {
    await rm(cacheDir, { recursive: true, force: true })
    throw error
  }
}

type ApiHandler = (request: Request, path: string) =>
  ApiResult | Promise<ApiResult>

type ApiResult = Parameters<Route["fulfill"]>[0] | "abort" | undefined

type Scenario = (
  handleApi: ApiHandler,
  options?: { actor?: "SELLER" | "BUYER" | "GUEST" }
) => Promise<Page>

async function configureScenario(
  origin: string,
  page: Page,
  unexpected: string[],
  handleApi: ApiHandler,
  options: { actor?: "SELLER" | "BUYER" | "GUEST" } = {}
) {
  const context = page.context()
  context.on("weberror", (failure) => unexpected.push(failure.error().message))
  await context.addInitScript(({ actor, appOrigin }) => {
    if (location.origin === appOrigin && actor !== "SELLER")
      localStorage.setItem("qa-actor", actor)
  }, { actor: options.actor ?? "SELLER", appOrigin: origin })
  await context.route("**/*", async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin) return route.abort()
    if (url.pathname === "/src/shared/auth/keycloak.ts")
      return route.fulfill({ contentType: "text/javascript", body: identityModule })
    if (!url.pathname.startsWith("/api/")) return route.continue()
    if (request.method() === "GET" && url.pathname === "/api/v1/users/me") {
      const authorization = request.headers()["authorization"]
      if (authorization !== "Bearer qa-BUYER" && authorization !== "Bearer qa-SELLER") {
        unexpected.push("GET /api/v1/users/me omitted the synthetic bearer token")
        return route.fulfill(errorDto(401, "UNAUTHORIZED"))
      }
      const role = authorization === "Bearer qa-BUYER" ? "BUYER" : "SELLER"
      return route.fulfill(json(200, {
        userId: role === "BUYER" ? buyerId : sellerId,
        role,
        email: `${role.toLowerCase()}@example.test`,
        emailVerified: true,
        organizationName: `QA ${role}`,
        verificationStatus: "VERIFIED",
      }))
    }
    try {
      const response = await handleApi(request, url.pathname)
      if (response === "abort") return route.abort("failed")
      if (response) return route.fulfill(response)
      unexpected.push(`${request.method()} ${url.pathname}`)
    } catch (error) {
      unexpected.push(`${request.method()} ${url.pathname}\n${String(error)}`)
    }
    return route.fulfill(errorDto(500, "QA_UNEXPECTED_REQUEST"))
  })
  await page.clock.install({ time: now })
  return page
}

export const test = base.extend<
  { scenario: Scenario; load: (page: Page, path: string) => Promise<void> },
  { frontend: Awaited<ReturnType<typeof startFrontend>> }
>({
  frontend: [async ({ browserName }, provideFixture) => {
    assert.equal(browserName, "chromium")
    const frontend = await startFrontend()
    try {
      await provideFixture(frontend)
    } finally {
      await frontend.close()
    }
  }, { scope: "worker" }],
  scenario: async ({ frontend, page }, provideFixture) => {
    const unexpected: string[] = []
    try {
      await provideFixture((handleApi, options) =>
        configureScenario(frontend.origin, page, unexpected, handleApi, options)
      )
    } finally {
      assert.deepEqual(unexpected, [])
    }
  },
  load: async ({ frontend }, provideFixture) => {
    await provideFixture(async (page, path) => {
      await page.goto(frontend.origin + path, { waitUntil: "domcontentloaded" })
    })
  },
})
