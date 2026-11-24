// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { reportClientFailure } from "@/shared/api/telemetry"
import { RenderErrorBoundary } from "@/shared/ui/render-error-boundary"

const safeTraceId = "3ac365cb08bb498284c5526f4936cd34"

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  window.history.replaceState(null, "", "/")
})

describe("render recovery", () => {
  it("isolates a rejected render, focuses the fallback, and resets on navigation", () => {
    const diagnostic = vi.spyOn(console, "warn").mockImplementation(() => undefined)
    const secret = "PRIVATE-TOKEN-AND-RAW-EXCEPTION"
    function Broken(): never {
      throw new Error(secret)
    }
    const page = (key: string, safe: boolean) => (
      <>
        <header>Điều hướng</header>
        <RenderErrorBoundary scope="route" resetKey={key}>
          {safe ? <div>Trang đã tải</div> : <Broken />}
        </RenderErrorBoundary>
      </>
    )
    const view = render(page("broken", false), { onCaughtError: () => undefined })
    const heading = screen.getByRole("heading", { name: "Không thể hiển thị trang" })
    expect(document.activeElement).toBe(heading)
    expect(screen.getByRole("banner").textContent).toBe("Điều hướng")
    expect(screen.getByRole("button", { name: "Tải lại trang" })).toBeDefined()
    expect(screen.getByRole("link", { name: "Về trang chủ" }).getAttribute("href")).toBe("/")
    expect(screen.queryByText(secret)).toBeNull()
    expect(diagnostic).toHaveBeenCalledWith("AgriContract", expect.objectContaining({ category: "render", scope: "route" }))
    expect(JSON.stringify(diagnostic.mock.calls)).not.toContain(secret)
    view.rerender(page("recovered", true))
    expect(screen.getByText("Trang đã tải")).toBeDefined()
    expect(screen.queryByRole("heading", { name: "Không thể hiển thị trang" })).toBeNull()
  })
})

describe("client failure diagnostics", () => {
  it("captures a release and correlation ID without raw paths or additional values", () => {
    vi.stubEnv("VITE_APP_VERSION", "81b7860")
    window.history.replaceState(null, "", "/contracts/secret-contract/milestones/secret-file?otp=123456")
    const diagnostic = vi.spyOn(console, "warn").mockImplementation(() => undefined)
    const failure = { category: "http", status: 503, traceId: safeTraceId, operation: "write", token: "secret-token" } as const
    reportClientFailure(failure)
    expect(diagnostic).toHaveBeenCalledWith("AgriContract", {
      event: "frontend.failure",
      category: "http",
      version: "81b7860",
      route: "/contracts/:id/milestones/:milestoneId",
      status: 503,
      operation: "write",
      traceId: safeTraceId,
    })
  })

  it("drops malformed identifiers and contains diagnostic failures", () => {
    vi.stubEnv("VITE_APP_VERSION", "token with raw payload")
    const diagnostic = vi.spyOn(console, "warn").mockImplementation(() => undefined)
    const failure = { category: "network", status: 0, traceId: "OTP=123456", operation: "read" } as const
    reportClientFailure(failure)
    expect(diagnostic).toHaveBeenCalledWith("AgriContract", {
      event: "frontend.failure",
      category: "network",
      version: "unversioned",
      route: "/",
      operation: "read",
    })
    diagnostic.mockImplementation(() => { throw new Error("unavailable") })
    expect(() => reportClientFailure(failure)).not.toThrow()
  })
})
