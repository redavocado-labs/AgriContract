// @vitest-environment jsdom
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { useAuthStore } from "@/shared/auth/auth-store"
import { RequireRole } from "@/features/auth/components/auth-guards"
import { milestoneDetailSchema } from "@/features/milestones/api/milestones"
import { BreachCasePanel } from "@/features/milestones/components/breach-case-panel"
import { DisputeExitPanel } from "@/features/milestones/components/dispute-exit-panel"
import { RemedyRetryPage } from "@/routes/remedy-retry-page"
import { initialBreachCases, initialDetails } from "../src/mocks/bf04-fixtures"

const stub = vi.hoisted(() => ({
  post: vi.fn(),
  get: vi.fn(),
  fresh: vi.fn(),
  forceLogin: vi.fn(),
  consume: vi.fn(),
  toast: vi.fn(),
}))
vi.mock("@/shared/api/client", () => ({
  newIdempotencyKey: () => crypto.randomUUID(),
  apiClient: { post: stub.post, get: stub.get },
}))
vi.mock("@/features/auth/hooks/use-fresh-login", () => ({
  useFreshLogin: () => ({
    ensureFreshLogin: stub.fresh,
    handleStepUpError: stub.forceLogin,
    consumeIntent: stub.consume,
  }),
}))
vi.mock("@/features/contracts/components/contracts-layout", () => ({
  ContractsLayout: ({ children }: { children: React.ReactNode }) => (
    <>{children}</>
  ),
}))
vi.mock("@/shared/ui/toast", () => ({ useToast: () => ({ add: stub.toast }) }))

const hash = "a".repeat(64)
const otp = {
  otpId: "3aa103a1-0912-4000-9000-000000000142",
  expiresAt: "2099-11-26T10:00:00Z",
}
const details = initialDetails()
const atIndex = (index: number) =>
  milestoneDetailSchema.parse(
    details.find((row) => row.milestone.milestoneIndex === index)
  )

function show(node: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>{node}</QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  stub.post.mockReset()
  stub.get.mockReset()
  stub.fresh.mockReset().mockResolvedValue(true)
  stub.forceLogin.mockResolvedValue(false)
  useAuthStore.setState({
    userId: "actor",
    roles: ["SELLER"],
    status: "ready",
    authenticated: true,
  })
})
afterEach(cleanup)

async function enterOtp(user: ReturnType<typeof userEvent.setup>) {
  const group = await screen.findByRole("group", { name: "Nhập mã OTP" })
  await user.click(within(group).getAllByRole("textbox")[0])
  await user.paste("123456")
  await user.click(screen.getByRole("button", { name: "Xác nhận OTP" }))
}

describe("BF-04 dispute exits", () => {
  it("binds seller concession to the server refund and requests a fresh login before sending OTP", async () => {
    const user = userEvent.setup()
    const detail = atIndex(4)
    stub.fresh.mockResolvedValueOnce(false)
    stub.post
      .mockResolvedValueOnce({ data: otp })
      .mockResolvedValueOnce({ status: 204 })
    show(<DisputeExitPanel detail={detail} hash={hash} role="SELLER" />)
    await user.click(screen.getByRole("button", { name: "Nhận lỗi" }))
    expect(screen.getByText(/37\.050\.000 đồng/)).toBeDefined()
    await user.click(screen.getByText(/Tôi (chấp nhận|đồng ý)/))
    await user.click(screen.getByRole("button", { name: "Gửi mã OTP" }))
    expect(stub.fresh).toHaveBeenCalledWith(
      expect.objectContaining({ step: "initiate" })
    )
    expect(stub.post).not.toHaveBeenCalled()
    await user.click(screen.getByRole("button", { name: "Gửi mã OTP" }))
    await waitFor(() => expect(stub.post).toHaveBeenCalledTimes(1))
    await enterOtp(user)
    await waitFor(() => expect(stub.toast).toHaveBeenCalled())
    expect(stub.post).toHaveBeenNthCalledWith(
      1,
      expect.stringMatching(/\/dispute\/concede\/initiate$/),
      { consentStatementVersion: "1.0", presentedContentHash: hash },
      { headers: { "Idempotency-Key": expect.any(String) } }
    )
    expect(stub.post).toHaveBeenNthCalledWith(
      2,
      expect.stringMatching(/\/dispute\/concede\/verify$/),
      { otpId: otp.otpId, otpCode: "123456" },
      { headers: { "Idempotency-Key": expect.any(String) } }
    )
  })

  it("sends the other party's exact settlement proposal through OTP and forbids self-acceptance", async () => {
    const detail = atIndex(11)
    const user = userEvent.setup()
    stub.post
      .mockResolvedValueOnce({ data: otp })
      .mockResolvedValueOnce({ status: 204 })
    const seller = show(
      <DisputeExitPanel detail={detail} hash={hash} role="SELLER" />
    )
    expect(
      screen.queryByRole("button", { name: "Chấp nhận đề xuất" })
    ).toBeNull()
    expect(screen.getByRole("button", { name: "Rút đề xuất" })).toBeDefined()
    seller.unmount()

    show(<DisputeExitPanel detail={detail} hash={hash} role="BUYER" />)
    expect(screen.getByText("25.000.000 ₫")).toBeDefined()
    await user.click(screen.getByRole("button", { name: "Chấp nhận đề xuất" }))
    await user.click(screen.getByText(/Tôi (chấp nhận|đồng ý)/))
    await user.click(screen.getByRole("button", { name: "Gửi mã OTP" }))
    await waitFor(() => expect(stub.post).toHaveBeenCalledTimes(1))
    await enterOtp(user)
    await waitFor(() => expect(stub.toast).toHaveBeenCalled())
    expect(stub.post.mock.calls[0].slice(0, 2)).toEqual([
      expect.stringMatching(/\/dispute\/settlement\/initiate$/),
      {
        agreedSellerEntitlementAmount: "925000000.00",
        consentStatementVersion: "1.0",
        presentedContentHash: hash,
      },
    ])
    expect(stub.post.mock.calls[1][0]).toMatch(/\/dispute\/settlement\/verify$/)
  })

  it("keeps settlement and concession closed when flags or refund are unavailable", () => {
    const detail = atIndex(4)
    show(
      <DisputeExitPanel
        hash={hash}
        role="SELLER"
        detail={{
          ...detail,
          disputeExit: {
            ...detail.disputeExit!,
            buyerPositionRefundAmount: undefined,
          },
        }}
      />
    )
    expect(screen.queryByRole("button", { name: "Nhận lỗi" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Đề xuất thỏa thuận" })
    ).toBeNull()
  })
})

describe("BF-04 roles and contest window", () => {
  it.each(["BUYER", "SELLER"] as const)(
    "shows an in-window form only to the blamed party (%s)",
    async (role) => {
      const detail = atIndex(9)
      stub.get.mockResolvedValue({
        data: initialBreachCases().find(
          (item) => item.breachCaseId === detail.breachCaseId
        ),
      })
      show(<BreachCasePanel detail={detail} role={role} canAct />)
      await screen.findByText("Hồ sơ tranh chấp")
      expect(!!screen.queryByRole("button", { name: "Gửi phản đối" })).toBe(
        role === "SELLER"
      )
    }
  )

  it("does not offer contest past the server deadline even if the response still says OPEN", async () => {
    const detail = atIndex(9)
    const breach = initialBreachCases().find(
      (item) => item.breachCaseId === detail.breachCaseId
    )!
    stub.get.mockResolvedValue({
      data: { ...breach, contestDeadline: "2000-11-26T10:00:00Z" },
    })
    show(<BreachCasePanel detail={detail} role="SELLER" canAct />)
    await screen.findByText("Hồ sơ tranh chấp")
    expect(screen.queryByRole("button", { name: "Gửi phản đối" })).toBeNull()
  })

  it("POSTs a contest and renders a Vietnamese rejection if the deadline closed on the server", async () => {
    const user = userEvent.setup()
    const detail = atIndex(9)
    const breach = initialBreachCases().find(
      (item) => item.breachCaseId === detail.breachCaseId
    )!
    stub.get.mockResolvedValue({ data: breach })
    stub.post.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 409,
        data: { status: 409, code: "BREACH_CASE_ACTION_NOT_ALLOWED" },
      },
    })
    show(<BreachCasePanel detail={detail} role="SELLER" canAct />)
    await user.type(
      await screen.findByLabelText(/Lý do phản đối/),
      "Cần kiểm tra lại"
    )
    await user.click(screen.getByRole("button", { name: "Gửi phản đối" }))
    expect(stub.post).toHaveBeenCalledWith(
      expect.stringMatching(/\/contest$/),
      { reason: "Cần kiểm tra lại", evidenceFileIds: [] },
      { headers: { "Idempotency-Key": expect.any(String) } }
    )
    expect((await screen.findByRole("alert")).textContent).toContain(
      "hạn phản đối"
    )
  })

  it.each(["OPERATOR", "ADMIN", "ARBITRATOR", "INSPECTOR"] as const)(
    "only gives OPERATOR the retry form (%s)",
    (role) => {
      useAuthStore.setState({ roles: [role] })
      show(
        <MemoryRouter
          initialEntries={[
            "/operations/remedy?contractId=3aa103a1-0912-4000-9000-000000000142",
          ]}
        >
          <Routes>
            <Route element={<RequireRole roles={["OPERATOR"]} />}>
              <Route path="/operations/remedy" element={<RemedyRetryPage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      )
      expect(
        !!screen.queryByRole("button", { name: "Gửi lại lệnh tiền lỗi" })
      ).toBe(role === "OPERATOR")
    }
  )
})
