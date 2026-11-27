// SRS IV.2.1 Supported browsers: main public screens render cleanly at 1366x900 with no JS errors
// and no horizontal overflow on both Chromium and Firefox.
import { expect, test } from "@playwright/test"
import { coffeeListing } from "./api"

let listingId = ""

test.beforeAll(async () => {
  listingId = (await coffeeListing()).listingId
})

const PUBLIC_PAGES = [
  { id: "010", path: "/", title: /.+/, heading: true },
  { id: "020", path: "/listing", title: "Sàn nguồn hàng", heading: true },
  { id: "030", path: "/prices", title: "Bảng giá tham khảo", heading: true },
  { id: "040", path: "/how-it-works", title: "Sáu bước từ lúc bắt tay đến khi tiền về tài khoản", heading: false },
  { id: "050", path: "/escrow", title: "Ký quỹ tự thực thi là gì?", heading: false },
  { id: "060", path: "/login", title: "Đăng nhập", heading: true },
]

for (const { id, path, title, heading } of PUBLIC_PAGES) {
  test(`ST-BRW-${id} Public screen ${path} renders without JS error or horizontal overflow`, async ({ page }) => {
    const errors: string[] = []
    page.on("pageerror", (err) => errors.push(err.message))

    await page.goto(path)
    const target = heading
      ? page.getByRole("heading", { level: 1, name: title }).first()
      : page.getByText(title).first()
    await expect(target).toBeVisible({ timeout: 20_000 })

    const { clientWidth, scrollWidth } = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
    expect(errors).toEqual([])
  })
}

test("ST-BRW-070 Public listing detail renders without JS error or horizontal overflow", async ({ page }) => {
  const errors: string[] = []
  page.on("pageerror", (err) => errors.push(err.message))

  await page.goto(`/listings/${listingId}`)
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })

  const { clientWidth, scrollWidth } = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
  expect(errors).toEqual([])
})
