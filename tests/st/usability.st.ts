// SRS IV.2.1 Usability checks through the web client on a real stack (Report 5.4, sheet Usability). Each case
// prepares what it needs, so one failure never stops the others:
// - inline field validation keeps the values already entered
// - money renders in VND with thousand separators
// - dates render as dd/MM/yyyy and server times in Asia/Ho_Chi_Minh
// - a blocked action states its reason (KYC still pending)
import { expect, test, type Page } from "@playwright/test"
import { coffeeListing } from "./api"
import { FUNDING_LEAD, businessDaysAfter, register, session, submitProfile, todayIct, verifyEmail } from "./fixtures"

/** Buyer sends an offer through the UI and lands on the contract page. */
async function offerFromUi(page: Page, listingId: string) {
  await page.goto(`/listings/${listingId}`)
  await page.getByRole("link", { name: "Gửi đề nghị" }).click()
  await page.waitForURL(/\/contracts\/new/)
  await page.getByLabel("Khối lượng").first().fill("1000")
  await page.getByLabel("Ngày giao dự kiến").first().fill(businessDaysAfter(todayIct(), FUNDING_LEAD + 5))
  await page.getByRole("button", { name: "Gửi đề nghị" }).click()
  await page.waitForURL(/\/contracts\/[0-9a-f-]{36}$/)
  await expect(page.getByText("Điều khoản hiện hành")).toBeVisible({ timeout: 20_000 })
}

test("ST-USA-010 Inline field validation shows beside the field and preserves other entered values", async ({ browser }) => {
  const { listingId } = await coffeeListing()
  const buyer = await session(browser, "buyer1")
  await buyer.goto(`/contracts/new?listingId=${listingId}`)
  await expect(buyer.getByRole("heading", { name: "Tạo đề nghị hợp đồng cà phê", level: 1 })).toBeVisible()
  const moisture = buyer.getByLabel("Độ ẩm tối đa")
  await moisture.fill("11.5")
  await buyer.getByLabel("Đơn giá thoả thuận").fill("95000") // two decimals missing
  await buyer.getByRole("button", { name: "Gửi đề nghị" }).click()
  await expect(buyer.getByText("Nhập VND/kg với đúng 2 chữ số thập phân (vd 95000.00).")).toBeVisible()
  await expect(moisture).toHaveValue("11.5")
  await expect(buyer).toHaveURL(/\/contracts\/new/)
})

// SRS IV.2.1 Money presentation: VND, thousands separators and two decimal places (96.000,00 ₫ in vi-VN)
test("ST-USA-020 Money values display in VND with thousand separators and two decimals", async ({ browser }) => {
  const { listingId } = await coffeeListing()
  const buyer = await session(browser, "buyer1")
  await offerFromUi(buyer, listingId)
  const main = await buyer.getByRole("main").innerText()
  expect.soft(main).toMatch(/96\.000,00\s*₫/) // unit price
  expect.soft(main).toMatch(/96\.000\.000,00\s*₫/) // total value of 1,000 kg
  await buyer.goto(`/listings/${listingId}`)
  await expect(buyer.getByText(/96\.000,00\s*₫\/kg/).first()).toBeVisible({ timeout: 5000 })
})

test("ST-USA-030 Dates display as dd/MM/yyyy and server timestamps use Asia/Ho_Chi_Minh", async ({ browser }) => {
  const { listingId } = await coffeeListing()
  // a fresh sign-in: requesting a signing code needs a login younger than 300 s (SRS IV.2.3)
  const buyer = await session(browser, "buyer1")
  await offerFromUi(buyer, listingId)
  const delivery = businessDaysAfter(todayIct(), FUNDING_LEAD + 5).split("-").reverse().join("/")
  await expect(buyer.getByText(delivery).first()).toBeVisible()
  const panel = buyer.getByRole("region", { name: "Ký hợp đồng" })
  await panel.getByRole("checkbox").check()
  await panel.getByRole("button", { name: "Gửi mã OTP" }).click()
  await expect(panel.getByText(/Mã hết hạn lúc/)).toBeVisible({ timeout: 20_000 })
  await expect(panel.getByText(/giờ Việt Nam/)).toBeVisible()
})

test("ST-USA-040 Blocked actions explain the exact reason (unverified KYC buyer)", async ({ browser }) => {
  const { listingId } = await coffeeListing()
  const email = `st-usa-${Date.now()}@test.com`
  const since = new Date(Date.now() - 5000)
  const page = await (await browser.newContext()).newPage()
  await register(page, email, "Người mua")
  await submitProfile(page, "Cong ty Cho Duyet")
  await verifyEmail(page, email, since)
  await expect(page.getByText("Hồ sơ đang chờ Operator xác minh", { exact: false })).toBeVisible()
  // an offer is refused with the reason on screen, not only a disabled control
  await page.goto(`/contracts/new?listingId=${listingId}`)
  await expect(page.getByText("Chỉ bên mua có hồ sơ đã xác minh mới gửi được đề nghị hợp đồng.")).toBeVisible()
  await expect(page.getByRole("button", { name: "Gửi đề nghị" })).toBeDisabled()
})
