// BF-02 Plot & Listing Publication, as the marketplace shows it. The seller's plot, product and listing are created
// through the API (the web client has no seller screens yet); a Guest then finds and reads the listing in the UI.
import { expect, test, type Page } from "@playwright/test"
import { coffeeListing } from "./api"

test.describe.serial("BF-02 a published listing on the marketplace", () => {
  let page: Page
  let listing: { listingId: string; productName: string }

  test.beforeAll(async ({ browser }) => {
    page = await (await browser.newContext()).newPage()
  })

  test("ST-BF02-010 Seller publishes a coffee listing from a checked plot (API: no seller screen yet)", async () => {
    listing = await coffeeListing()
    expect(listing.listingId).toMatch(/^[0-9a-f-]{36}$/)
  })

  test("ST-BF02-020 Guest opens the marketplace without signing in", async () => {
    await page.goto("/listing")
    await expect(page.getByRole("heading", { name: "Sàn nguồn hàng", level: 1 })).toBeVisible()
    await expect(page.getByRole("article").first()).toBeVisible()
  })

  test("ST-BF02-030 Guest reads the listing: price, quantities, declared quality, no seller contact data", async () => {
    await page.goto(`/listings/${listing.listingId}`)
    const main = page.getByRole("main")
    await expect(main.getByRole("heading", { name: listing.productName, level: 1 })).toBeVisible()
    await expect(main.getByText("Đang mở bán")).toBeVisible()
    await expect(main.getByText("96.000 ₫/kg")).toBeVisible()
    await expect(main.getByText("5.000 kg").first()).toBeVisible()
    await expect(main.getByText("Robusta", { exact: true }).first()).toBeVisible()
    // SRS IV.2.3: e-mail, phone and address are never shown on public pages
    await expect(main.getByText(/@test\.com|seller1|0901234567/)).toHaveCount(0)
  })

  test("ST-BF02-040 Guest who wants to offer is sent to sign in", async () => {
    await page.getByRole("link", { name: "Gửi đề nghị" }).click()
    await page.waitForURL(/\/realms\/agricontract\/|\/login/)
    expect(page.url()).toMatch(/\/realms\/agricontract\/|\/login/)
  })
})
