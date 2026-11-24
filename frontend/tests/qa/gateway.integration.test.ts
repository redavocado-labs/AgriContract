import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { http, HttpResponse } from "msw"
import { setupServer } from "msw/node"

const session = vi.hoisted(() => ({
  keycloak: { authenticated: true },
  login: vi.fn(async () => {}),
  refreshAccessToken: vi.fn(async () => "qa-local-token"),
}))
vi.mock("@/shared/auth/keycloak", () => session)

const gateway = "http://gateway.qa.test"
const contractId = "00000000-0000-4000-8000-000000000001"
const key = "00000000-0000-4000-8000-000000000002"
const otpId = "00000000-0000-4000-8000-000000000003"
const contractPath = `${gateway}/api/v1/contracts/${contractId}`
const server = setupServer()

vi.stubEnv("VITE_API_BASE_URL", gateway)
vi.stubEnv("VITE_MOCK_API", "")
vi.stubGlobal("window", { location: { origin: "http://frontend.qa.test" } })
const { withdrawOffer } = await import("@/features/contracts/api/contracts")
const { verifySigning } = await import("@/features/contracts/api/signing")
const { parseApiError } = await import("@/shared/api/errors")

beforeAll(() => server.listen({ onUnhandledRequest: "error" }))
beforeEach(() => vi.clearAllMocks())
afterEach(() => {
  server.resetHandlers()
  vi.useRealTimers()
})
afterAll(() => {
  server.close()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function expectWrite(request: Request) {
  expect(request.headers.get("Authorization")).toBe("Bearer qa-local-token")
  expect(request.headers.get("Idempotency-Key")).toBe(key)
}

describe("frozen contract write APIs at the gateway boundary", () => {
  it.each([
    [401, "UNAUTHORIZED"],
    [403, "FORBIDDEN"],
    [409, "CONTRACT_NOT_WITHDRAWABLE"],
    [422, "VALIDATION_FAILED"],
    [429, "RATE_LIMIT_EXCEEDED"],
    [503, "SERVICE_UNAVAILABLE"],
  ])("does not replay HTTP %i or treat it as a successful withdrawal", async (status, code) => {
    let writes = 0
    server.use(http.post(`${contractPath}/withdraw`, ({ request }) => {
      writes++
      expectWrite(request)
      return HttpResponse.json({ status, code, message: code }, { status })
    }))
    const failure = await withdrawOffer(contractId, key).catch((error: unknown) => error)
    expect(parseApiError(failure)).toMatchObject({ status, code })
    expect(writes).toBe(1)
    expect(session.login).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
  })

  it("sends the caller's key on an explicit replay after the first response is lost", async () => {
    let writes = 0
    server.use(http.post(`${contractPath}/withdraw`, ({ request }) => {
      writes++
      expectWrite(request)
      // This fixture models the frozen original-success replay, not backend deduplication.
      return writes === 1 ? HttpResponse.error() : new HttpResponse(null, { status: 204 })
    }))
    await expect(withdrawOffer(contractId, key)).rejects.toThrow()
    expect(writes).toBe(1)
    await expect(withdrawOffer(contractId, key)).resolves.toBeUndefined()
    expect(writes).toBe(2)
  })

  it("expires a signing challenge at the boundary before sending any OTP request", async () => {
    vi.useFakeTimers({ toFake: ["Date"] })
    const atExpiry = "2026-11-24T08:00:00.000Z"
    vi.setSystemTime(new Date(atExpiry))
    let writes = 0
    server.use(http.post(`${contractPath}/sign/verify`, () => {
      writes++
      return new HttpResponse(null, { status: 204 })
    }))
    await expect(verifySigning(contractId, { otpId, expiresAt: atExpiry }, "123456", key))
      .rejects.toThrow(/đã hết hạn/)
    expect(writes).toBe(0)
  })

  it("sends only the bound challenge ID and OTP and preserves a step-up rejection", async () => {
    vi.useFakeTimers({ toFake: ["Date"] })
    vi.setSystemTime(new Date("2026-11-24T08:00:00.000Z"))
    let writes = 0
    server.use(http.post(`${contractPath}/sign/verify`, async ({ request }) => {
      writes++
      expectWrite(request)
      expect(await request.json()).toEqual({ otpId, otpCode: "123456" })
      return HttpResponse.json(
        { status: 403, code: "STEP_UP_REQUIRED", message: "step up" },
        { status: 403 }
      )
    }))
    const failure = await verifySigning(
      contractId,
      { otpId, expiresAt: "2026-11-24T08:00:01.000Z" },
      "123456",
      key
    ).catch((error: unknown) => error)
    expect(parseApiError(failure)).toMatchObject({ status: 403, code: "STEP_UP_REQUIRED" })
    expect(writes).toBe(1)
    expect(session.login).not.toHaveBeenCalled()
  })
})
