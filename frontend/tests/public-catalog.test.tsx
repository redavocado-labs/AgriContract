// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react"
import { http, HttpResponse } from "msw"
import { setupServer } from "msw/node"
import { Link, MemoryRouter, Route, Routes } from "react-router-dom"
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest"
import { useAuthStore } from "@/shared/auth/auth-store"

const session = vi.hoisted(() => ({
  keycloak: { authenticated: false },
  login: vi.fn(async () => {}),
  logout: vi.fn(async () => {}),
  refreshAccessToken: vi.fn(async () => "local-viewer-token"),
}))
vi.mock("@/shared/auth/keycloak", () => session)

const gateway = "http://gateway.public.test"
vi.stubEnv("VITE_API_BASE_URL", gateway)
vi.stubEnv("VITE_MOCK_API", "")
const { ListingDetailPage } = await import("@/routes/listing-detail-page")
const { MarketplacePage } = await import("@/routes/marketplace-page")
const { PricesPage } = await import("@/routes/prices-page")
const { PublicReputationPage } = await import("@/routes/public-reputation-page")

const server = setupServer()
const first = "00000000-0000-4000-8000-000000000001"
const second = "00000000-0000-4000-8000-000000000002"
const unknown = "00000000-0000-4000-8000-000000000003"
const seller = "00000000-0000-4000-8000-000000000004"
const buyer = "00000000-0000-4000-8000-000000000005"

const listing = (id: string, productName: string) => ({
  listingId: id,
  sellerId: seller,
  productName,
  commodity: "COFFEE",
  unitOfMeasure: "KG",
  offeredQuantity: 1000,
  quantityAvailable: 750,
  minOrderQuantity: 100,
  askingUnitPrice: "89123.45",
  status: "PARTIALLY_COMMITTED",
  listingVersionToken: "server-version",
  declaredQualitySpec: {
    commodity: "COFFEE", type: "ROBUSTA",
    moisturePercent: 12, foreignMatterPercent: 1, blackBrokenBeansPercent: 2,
  },
})

beforeAll(() => server.listen({ onUnhandledRequest: "error" }))
beforeEach(() => {
  session.keycloak.authenticated = false
  useAuthStore.setState({ authenticated: false, userId: undefined, roles: [], status: "ready" })
  vi.clearAllMocks()
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
})
afterAll(() => {
  server.close()
  vi.unstubAllEnvs()
})

function open(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Link to={`/listings/${second}`}>Mở lô thứ hai</Link>
        <Link to={`/listings/${unknown}`}>Mở lô chưa biết</Link>
        <Link to={`/reputation/${second}`}>Mở hồ sơ thứ hai</Link>
        <Routes>
          <Route path="/listing" element={<MarketplacePage />} />
          <Route path="/listings/:id" element={<ListingDetailPage />} />
          <Route path="/prices" element={<PricesPage />} />
          <Route path="/reputation/:userId" element={<PublicReputationPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it("requests anonymous listings and links the server resource IDs", async () => {
  let reads = 0
  server.use(http.get(`${gateway}/api/v1/listings`, ({ request }) => {
    reads++
    expect(request.headers.get("Authorization")).toBeNull()
    expect(new URL(request.url).searchParams.get("page")).toBe("0")
    return HttpResponse.json({
      content: [{ ...listing(first, "Lô Đắk Lắk"), sellerReputation: null }],
      page: 0, size: 12, totalPages: 1, totalElements: 1,
      first: true, last: true, empty: false, reputationStatus: "SIGN_IN_REQUIRED",
    })
  }))
  open("/listing")
  expect(await screen.findByRole("heading", { name: "Lô Đắk Lắk" })).toBeTruthy()
  expect(screen.getByRole("link", { name: "Xem chi tiết Lô Đắk Lắk" }).getAttribute("href"))
    .toBe(`/listings/${first}`)
  expect(screen.getByRole("link", { name: "Xem uy tín bên bán" }).getAttribute("href"))
    .toBe(`/reputation/${seller}`)
  expect(reads).toBe(1)
  expect(session.login).not.toHaveBeenCalled()
})

it("switches listing detail by ID and handles a missing listing", async () => {
  server.use(http.get(`${gateway}/api/v1/listings/:id`, ({ params, request }) => {
    expect(request.headers.get("Authorization")).toBeNull()
    if (params.id === first) return HttpResponse.json(listing(first, "Cà phê lô một"))
    if (params.id === second) return HttpResponse.json(listing(second, "Cà phê lô hai"))
    return HttpResponse.json({ code: "LISTING_NOT_FOUND", status: 404 }, { status: 404 })
  }))
  open(`/listings/${first}`)
  expect(await screen.findByRole("heading", { name: "Cà phê lô một" })).toBeTruthy()
  fireEvent.click(screen.getByRole("link", { name: "Mở lô thứ hai" }))
  expect(await screen.findByRole("heading", { name: "Cà phê lô hai" })).toBeTruthy()
  expect(screen.queryByText("Cà phê lô một")).toBeNull()
  fireEvent.click(screen.getByRole("link", { name: "Mở lô chưa biết" }))
  expect(await screen.findByText("Không tìm thấy nguồn hàng")).toBeTruthy()
})

it("offers a retry and rejects a valid response for the wrong listing", async () => {
  let reads = 0
  server.use(http.get(`${gateway}/api/v1/listings/${first}`, () => {
    reads++
    if (reads === 1) return HttpResponse.json(listing(second, "Lô không đúng"))
    return HttpResponse.json(listing(first, "Lô đã yêu cầu"))
  }))
  open(`/listings/${first}`)
  expect(await screen.findByText("Chưa tải được nguồn hàng")).toBeTruthy()
  expect(screen.queryByText("Lô không đúng")).toBeNull()
  fireEvent.click(screen.getByRole("button", { name: "Thử lại" }))
  expect(await screen.findByRole("heading", { name: "Lô đã yêu cầu" })).toBeTruthy()
  expect(reads).toBe(2)
})

it("opens an offer for the selected listing for a verified buyer", async () => {
  session.keycloak.authenticated = true
  useAuthStore.setState({ authenticated: true, userId: buyer, roles: ["BUYER"] })
  server.use(
    http.get(`${gateway}/api/v1/users/me`, () => HttpResponse.json({
      userId: buyer, role: "BUYER", verificationStatus: "VERIFIED",
    })),
    http.get(`${gateway}/api/v1/listings/${first}`, ({ request }) => {
      expect(request.headers.get("Authorization")).toBeNull()
      return HttpResponse.json(listing(first, "Lô mở bán"))
    }),
  )
  open(`/listings/${first}`)
  expect((await screen.findByRole("link", { name: "Gửi đề nghị" })).getAttribute("href"))
    .toBe(`/contracts/new?listingId=${first}`)
})

it("uses the signed-in score filter and drops enriched results on sign-out", async () => {
  session.keycloak.authenticated = true
  useAuthStore.setState({ authenticated: true, userId: buyer, roles: ["BUYER"] })
  server.use(
    http.get(`${gateway}/api/v1/users/me`, () => HttpResponse.json({ userId: buyer })),
    http.get(`${gateway}/api/v1/listings`, ({ request }) => {
      const signedIn = request.headers.has("Authorization")
      expect(new URL(request.url).searchParams.get("minSellerScore")).toBe(signedIn ? "80" : null)
      return HttpResponse.json({
        content: [{
          ...listing(first, "Nguồn hàng trên sàn"),
          sellerReputation: signedIn ? { score: 90, locked: false } : null,
        }],
        page: 0, size: 12, totalPages: 1, totalElements: 1,
        first: true, last: true, empty: false,
        reputationStatus: signedIn ? "AVAILABLE" : "SIGN_IN_REQUIRED",
      })
    }),
  )
  open("/listing?minSellerScore=80")
  expect(await screen.findByText("Điểm uy tín 90/100")).toBeTruthy()
  await act(() => {
    session.keycloak.authenticated = false
    useAuthStore.setState({ authenticated: false, userId: undefined, roles: [] })
  })
  expect(await screen.findByRole("heading", { name: "Nguồn hàng trên sàn" })).toBeTruthy()
  expect(screen.queryByText("Điểm uy tín 90/100")).toBeNull()
})

it("requires sign-in before requesting partner reputation", () => {
  open(`/reputation/${first}`)
  expect(screen.getByText("Đăng nhập để xem hồ sơ uy tín")).toBeTruthy()
  expect(session.refreshAccessToken).not.toHaveBeenCalled()
})

it("keys authenticated reputation reads by subject and shows absence of facts", async () => {
  session.keycloak.authenticated = true
  useAuthStore.setState({ authenticated: true, userId: buyer, roles: ["BUYER"] })
  server.use(
    http.get(`${gateway}/api/v1/users/me`, () => HttpResponse.json({}, { status: 404 })),
    http.get(`${gateway}/api/v1/users/:id`, ({ params, request }) => {
      expect(request.headers.get("Authorization")).toBe("Bearer local-viewer-token")
      if (params.id !== first) return HttpResponse.json({}, { status: 404 })
      return HttpResponse.json({
        userId: first, organizationName: "Hợp tác xã đối tác", role: "SELLER", verificationStatus: "VERIFIED",
      })
    }),
    http.get(`${gateway}/api/v1/reputation/:id/public-summary`, ({ params, request }) => {
      expect(request.headers.get("Authorization")).toBe("Bearer local-viewer-token")
      return HttpResponse.json({
        userId: params.id, score: params.id === first ? 79 : null, locked: false, lockHistory: [],
      })
    }),
  )
  open(`/reputation/${first}`)
  expect(await screen.findByText("79 / 100")).toBeTruthy()
  expect(await screen.findByText("Hợp tác xã đối tác")).toBeTruthy()
  fireEvent.click(screen.getByRole("link", { name: "Mở hồ sơ thứ hai" }))
  expect(await screen.findByText("Chưa có dữ liệu uy tín")).toBeTruthy()
  expect(screen.queryByText("79 / 100")).toBeNull()
  expect(screen.queryByText("Hợp tác xã đối tác")).toBeNull()
})

it("loads the latest price anonymously and shows when the selected province has no quotes", async () => {
  server.use(
    http.get(`${gateway}/api/v1/prices/COFFEE/:province/items`, ({ params, request }) => {
      expect(request.headers.get("Authorization")).toBeNull()
      return HttpResponse.json({
        commodity: "COFFEE", province: params.province,
        items: params.province === "dak-lak"
          ? [{ itemSlug: "ca-phe-nhan-xo", itemName: "Cà phê nhân xô" }]
          : [],
      })
    }),
    http.get(`${gateway}/api/v1/prices/COFFEE/dak-lak`, ({ request }) => {
      expect(request.headers.get("Authorization")).toBeNull()
      expect(new URL(request.url).searchParams.get("item")).toBe("ca-phe-nhan-xo")
      return HttpResponse.json({
        commodity: "COFFEE", province: "Đắk Lắk", provinceSlug: "dak-lak",
        itemSlug: "ca-phe-nhan-xo", itemName: "Cà phê nhân xô",
        price: "88500.00", capturedAt: "2026-11-10", ingestedAt: "2026-11-25T08:00:00Z",
        source: "VNSAT_SCRAPE", stale: true, referenceSource: null, reason: null,
      })
    }),
  )
  open("/prices")
  expect(await screen.findByText("88.500 ₫/kg")).toBeTruthy()
  expect(screen.getByText("Giá này đã cũ")).toBeTruthy()
  fireEvent.change(screen.getByLabelText("Tỉnh"), { target: { value: "lam-dong" } })
  expect(await screen.findByText("Chưa có giá cho tỉnh này")).toBeTruthy()
  expect(screen.queryByText("88.500 ₫/kg")).toBeNull()
})
