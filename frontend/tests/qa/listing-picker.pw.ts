import assert from "node:assert/strict"
import { expect } from "@playwright/test"
import { json, sellerId, test } from "./browser-fixture"

type ListingStatus = "AVAILABLE" | "PARTIALLY_COMMITTED" | "CLOSED"

function listing(
  sequence: number,
  productName: string,
  status: ListingStatus,
  quantityAvailable: number | null
) {
  return {
    listingId: `00000000-0000-4000-8000-${String(sequence).padStart(12, "0")}`,
    sellerId,
    productId: "00000000-0000-4000-8000-000000000021",
    categoryId: "00000000-0000-4000-8000-000000000022",
    productName,
    commodity: "COFFEE",
    unitOfMeasure: "KG",
    status,
    offeredQuantity: 1000,
    minOrderQuantity: 100,
    quantityAvailable,
    askingUnitPrice: "86500.00",
    listingVersionToken: "2026-11-24T07:00:00Z",
    regionText: "Đắk Lắk",
    registryEntryIds: ["00000000-0000-4000-8000-000000000023"],
    legacyQualitySpecMissing: quantityAvailable === null,
    cropYear: null,
    harvestSeason: null,
    certifications: null,
    declaredQualitySpec: quantityAvailable === null ? null : {
      commodity: "COFFEE",
      type: "ROBUSTA",
      moisturePercent: 12,
      foreignMatterPercent: 1,
      blackBrokenBeansPercent: 2,
    },
  }
}

test("FE-007 an eligible buyer can select a reservable source and receives that listing's offer form", async ({ scenario, load }) => {
  const available = listing(24, "Lô cà phê còn nguyên", "AVAILABLE", 1000)
  const selected = listing(25, "Lô cà phê còn một phần", "PARTIALLY_COMMITTED", 100)
  const closed = listing(26, "Lô cà phê đã đóng", "CLOSED", 500)
  const belowMinimum = listing(27, "Lô cà phê dưới mức tối thiểu", "PARTIALLY_COMMITTED", 50)
  const legacy = listing(28, "Lô cà phê chưa có tồn kho", "AVAILABLE", null)
  const rows = [available, selected, closed, belowMinimum, legacy]
  const returnedName = "Lô cà phê còn một phần tại thời điểm chọn"
  const detailReads: string[] = []
  const listingsPath = "/api/v1/listings"
  const detailPath = `${listingsPath}/${selected.listingId}`
  const page = await scenario((request, path) => {
    if (request.method() !== "GET") return
    if (path === listingsPath) {
      return json(200, {
        content: rows.map((row) => ({ ...row, sellerReputation: { score: null, locked: false } })),
        page: 0,
        size: 12,
        totalElements: rows.length,
        totalPages: 1,
        first: true,
        last: true,
        empty: false,
        reputationStatus: "AVAILABLE",
      })
    }
    if (path === detailPath) {
      detailReads.push(path)
      return json(200, {
        ...selected,
        productName: returnedName,
        askingUnitPrice: "87250.00",
        listingVersionToken: "2026-11-24T07:30:00Z",
      })
    }
  }, { actor: "BUYER" })

  await load(page, "/contracts/new")
  await expect(page.getByRole("button", { name: /Lô cà phê còn nguyên/ })).toBeVisible()
  const choosePartial = page.getByRole("button", { name: /Lô cà phê còn một phần/ })
  await expect(choosePartial).toBeVisible()
  for (const unavailable of [closed, belowMinimum, legacy]) {
    await expect(page.getByText(unavailable.productName, { exact: true })).toHaveCount(0)
  }
  assert.deepEqual(detailReads, [])

  await choosePartial.click()
  await expect(page).toHaveURL((url) =>
    url.pathname === "/contracts/new" && url.searchParams.get("listingId") === selected.listingId
  )
  await expect(page.getByRole("heading", { name: returnedName })).toBeVisible()
  const price = page.getByLabel(/Đơn giá thoả thuận/)
  await expect(price).toBeEnabled()
  await expect(price).toHaveValue("87250.00")
  assert.deepEqual(detailReads, [detailPath])
})
