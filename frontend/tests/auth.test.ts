import { describe, it, expect } from "vitest"
import { AxiosError, AxiosHeaders } from "axios"
import { identityFromToken } from "../src/shared/auth/auth-store"
import {
  INTENT_KEY,
  needsFreshLogin,
  readIntent,
} from "../src/shared/auth/step-up"
import { parseApiError } from "../src/shared/api/errors"

describe("identity and freshness", () => {
  it("takes identity from JWT, filters roles, never fabricates auth_time", () => {
    const result = identityFromToken("token", {
      sub: "user",
      iat: 100,
      realm_access: { roles: ["BUYER", "offline_access", "admin", "SELLER"] },
    })
    expect(result.roles).toEqual(["BUYER", "SELLER"])
    expect(result.authTime).toBeUndefined()
    expect(result.userId).toBe("user")
    expect(identityFromToken().authenticated).toBe(false)
  })
  it("requires step-up for missing, stale and implausible future auth time", () => {
    expect(needsFreshLogin(undefined, 300, 1000)).toBe(true)
    expect(needsFreshLogin(729, 300, 1000)).toBe(true)
    expect(needsFreshLogin(730, 300, 1000)).toBe(false)
    expect(needsFreshLogin(1100, 300, 1000)).toBe(true)
  })
})

function storageFor(value: unknown) {
  const data = new Map([[INTENT_KEY, JSON.stringify(value)]])
  return {
    getItem: (key: string) => data.get(key) ?? null,
    removeItem: (key: string) => data.delete(key),
  } as unknown as Storage
}
const intent = {
  action: "sign",
  contractId: "contract-1",
  step: "consent",
  userId: "buyer",
  returnUrl: "http://localhost:5173/account",
  requestedAt: 1000,
  maxAgeSeconds: 300,
}
describe("step-up intent", () => {
  it("restores the same actor, route and step after fresh authentication", () => {
    expect(
      readIntent(storageFor(intent), "buyer", intent.returnUrl, 1010, 1011)
    ).toEqual(intent)
  })
  it("does not restore stale authentication or another page", () => {
    expect(
      readIntent(storageFor(intent), "buyer", intent.returnUrl, 990, 1011)
    ).toBeUndefined()
    expect(
      readIntent(
        storageFor(intent),
        "buyer",
        "http://localhost:5173/",
        1010,
        1011
      )
    ).toBeUndefined()
  })
  it.each([
    { ...intent, userId: "another-user" },
    { ...intent, requestedAt: 1 },
    { ...intent, returnUrl: "https://external.test/" },
    { ...intent, maxAgeSeconds: "300" },
  ])("rejects invalid or cross-account intent", (invalid) => {
    const storage = storageFor(invalid)
    expect(
      readIntent(storage, "buyer", intent.returnUrl, 1010, 1011)
    ).toBeUndefined()
    expect(storage.getItem(INTENT_KEY)).toBeNull()
  })
})

function httpError(status: number, data: unknown) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError("failed", "ERR_BAD_RESPONSE", config, undefined, {
    status,
    data,
    statusText: "",
    headers: {},
    config,
  })
}
const envelope = (status: number, code: string, message: string) => ({
  timestamp: "2026-11-22T03:00:00Z",
  status,
  error: "Conflict",
  code,
  message,
  path: "/api/v1/contracts/c/sign/initiate",
  traceId: "trace-1",
})
describe("API formats", () => {
  it("reads the ErrorEnvelope code, message and traceId", () => {
    expect(
      parseApiError(
        httpError(403, envelope(403, "STEP_UP_REQUIRED", "Xác thực: hết hạn"))
      )
    ).toEqual({
      status: 403,
      code: "STEP_UP_REQUIRED",
      message: "Xác thực: hết hạn",
      traceId: "trace-1",
    })
  })
  it("does not read codes out of non-envelope bodies", () => {
    expect(
      parseApiError(httpError(409, { success: false, error: "OTP_EXPIRED: x" }))
        .code
    ).toBe("HTTP_409")
    expect(parseApiError(httpError(502, "<html>Bad gateway</html>")).code).toBe(
      "HTTP_502"
    )
    expect(parseApiError(new Error("network"))).toMatchObject({
      status: 0,
      code: "NETWORK_ERROR",
    })
  })
})
