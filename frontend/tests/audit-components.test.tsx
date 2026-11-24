// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { RequireRole } from "@/features/auth/components/auth-guards"
import { ContractPartyAuditTab } from "@/features/audit"
import { AdminAuditPage } from "@/routes/admin-audit-page"
import { useAuthStore } from "@/shared/auth/auth-store"

const stub = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock("@/shared/api/client", () => ({
  newIdempotencyKey: () => crypto.randomUUID(),
  apiClient: { get: stub.get },
}))
vi.mock("@/features/contracts/components/contracts-layout", () => ({
  ContractsLayout: ({ children }: { children: React.ReactNode }) => (
    <>{children}</>
  ),
}))

const h = (ch: string) => ch.repeat(64)
const contractId = "11111111-1111-4111-8111-111111111111"
const r1 = "22222222-2222-4222-8222-222222222201"
const r2 = "22222222-2222-4222-8222-222222222202"

function show(node: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  stub.get.mockReset()
  useAuthStore.setState({
    authenticated: true,
    userId: "party-1",
    roles: ["BUYER"],
    status: "ready",
  })
})
afterEach(cleanup)

describe("ContractPartyAuditTab", () => {
  it("renders newest-first without link-hash columns and verifies INTACT + NOT_ANCHORED", async () => {
    stub.get
      .mockResolvedValueOnce({
        data: {
          contractId,
          currentRecordHash: h("b"),
          anchorStatus: "NOT_ANCHORED",
          records: [
            {
              recordId: r1,
              sourceType: "CONTRACT_SIGNED",
              sourceEventType: "contract.signed",
              content: { contractId },
              recordHash: h("a"),
              createdAt: "2026-11-24T03:00:00Z",
            },
            {
              recordId: r2,
              sourceType: "CONTRACT_SETTLED",
              sourceEventType: "contract.settled",
              content: { contractId },
              recordHash: h("b"),
              createdAt: "2026-11-24T05:00:00Z",
            },
          ],
        },
      })
      .mockResolvedValueOnce({
        data: {
          scope: "CONTRACT",
          contractId,
          status: "INTACT",
          checkedRecords: 2,
          brokenRecordId: null,
          brokenReason: null,
          checkedAt: "2026-11-24T06:00:00Z",
          anchorStatus: "NOT_ANCHORED",
        },
      })

    show(<ContractPartyAuditTab contractId={contractId} active />)

    expect(await screen.findByText("Nguyên vẹn")).toBeDefined()
    expect(screen.getByText("chưa có")).toBeDefined()

    const rows = screen.getAllByRole("row")
    expect(rows[1]!.textContent).toContain("Hợp đồng hoàn tất")
    expect(rows[2]!.textContent).toContain("Hai bên ký hợp đồng")

    expect(screen.queryByText("Nối cùng chủ thể")).toBeNull()
    expect(screen.queryByText("Nối toàn hệ thống")).toBeNull()
    expect(screen.queryByText("Seq")).toBeNull()
  })

  it.each([
    ["RECORD_HASH_MISMATCH", "Nội dung bản ghi này đã bị sửa"],
    [
      "SUBJECT_LINK_BROKEN",
      "Mắt xích trước bản ghi này bị đứt (có bản ghi bị xoá hoặc chèn)",
    ],
  ] as const)(
    "highlights broken record and shows Vietnamese reason (%s)",
    async (brokenReason, expectedMessage) => {
      stub.get
        .mockResolvedValueOnce({
          data: {
            contractId,
            currentRecordHash: h("b"),
            anchorStatus: "NOT_ANCHORED",
            records: [
              {
                recordId: r1,
                sourceType: "CONTRACT_SIGNED",
                sourceEventType: "contract.signed",
                content: { contractId },
                recordHash: h("a"),
                createdAt: "2026-11-24T03:00:00Z",
              },
              {
                recordId: r2,
                sourceType: "MILESTONE_EVENT",
                sourceEventType: "milestone.seller_weighed",
                content: { sellerDeclaredWeight: 5000, declarationNumber: 1 },
                recordHash: h("b"),
                createdAt: "2026-11-24T04:00:00Z",
              },
            ],
          },
        })
        .mockResolvedValueOnce({
          data: {
            scope: "CONTRACT",
            contractId,
            status: "BROKEN",
            checkedRecords: 2,
            brokenRecordId: r2,
            brokenReason,
            checkedAt: "2026-11-24T06:00:00Z",
            anchorStatus: "NOT_ANCHORED",
          },
        })

      show(<ContractPartyAuditTab contractId={contractId} active />)

      const alert = await screen.findByRole("alert")
      expect(alert.textContent).toContain(expectedMessage)
      expect(alert.textContent).toContain(r2)
    }
  )
})

describe("AdminAuditPage and role guard", () => {
  it("blocks non-ADMIN roles from /admin/audit without fallback", () => {
    useAuthStore.setState({
      authenticated: true,
      userId: "buyer-1",
      roles: ["BUYER"],
      status: "ready",
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/admin/audit"]}>
          <Routes>
            <Route element={<RequireRole roles={["ADMIN"]} />}>
              <Route path="/admin/audit" element={<AdminAuditPage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )
    expect(
      screen.queryByText(/Mỗi lần bạn mở nhật ký/i)
    ).toBeNull()
    expect(stub.get).not.toHaveBeenCalled()
  })

  it("renders global chain, unreadable record, and GLOBAL_LINK_BROKEN verification for ADMIN", async () => {
    const user = userEvent.setup()
    useAuthStore.setState({
      authenticated: true,
      userId: "ad000000-0000-4000-8000-000000000001",
      roles: ["ADMIN"],
      status: "ready",
    })
    stub.get
      .mockResolvedValueOnce({
        data: {
          content: [
            {
              recordId: r2,
              globalSeq: 2,
              subjectType: null,
              subjectId: null,
              sourceType: null,
              sourceEventType: null,
              sourceEventId: null,
              content: null,
              recordHash: h("f"),
              prevHashSubject: null,
              prevHashGlobal: h("a"),
              createdAt: null,
            },
            {
              recordId: r1,
              globalSeq: 1,
              subjectType: "SYSTEM",
              subjectId: "SYSTEM",
              sourceType: "AUDIT_VIEWED",
              sourceEventType: "audit.viewed",
              sourceEventId: r1,
              content: {
                adminUserId: "ad000000-0000-4000-8000-000000000001",
                scope: "GLOBAL",
              },
              recordHash: h("a"),
              prevHashSubject: null,
              prevHashGlobal: null,
              createdAt: "2026-11-24T03:00:00Z",
            },
          ],
          page: 0,
          size: 20,
          totalElements: 2,
          totalPages: 1,
        },
      })
      .mockResolvedValueOnce({
        data: {
          scope: "GLOBAL",
          contractId: null,
          status: "BROKEN",
          checkedRecords: 2,
          brokenRecordId: r2,
          brokenReason: "GLOBAL_LINK_BROKEN",
          checkedAt: "2026-11-24T06:00:00Z",
          anchorStatus: "NOT_ANCHORED",
        },
      })

    show(<AdminAuditPage />)

    expect(
      (await screen.findAllByText("Bản ghi không đọc được")).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("Nối cùng chủ thể").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Nối toàn hệ thống").length).toBeGreaterThan(0)

    await user.click(screen.getByRole("button", { name: "Kiểm tra toàn chuỗi" }))
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "Mắt xích toàn hệ thống bị đứt"
      )
    )
  })
})
