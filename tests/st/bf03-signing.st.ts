// BF-03 Contract Signing & Activation and BF-04 Milestone Delivery & Settlement through the web client, as one chain:
// the buyer makes an offer from the listing page, the seller revises the price, both sign with the OTP from their
// mail, the contract turns ACTIVE once escrow locks the deposits; then the seller weighs the goods with a photo, the
// buyer weighs on receipt and confirms clean, the single milestone and the contract settle. A second offer is
// withdrawn before any signature. The seller's listing is created through the API.
import { expect, test, type Page } from "@playwright/test"
import { api, coffeeListing, decodeMail, mail } from "./api"
import { FUNDING_LEAD, NOT_A_BUSINESS_DAY, businessDaysAfter, contractIdOf, expectStatus, session, todayIct } from "./fixtures"

// 1x1 PNG used as the weighing photo
const PIXEL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwADhQGAWjR9awAAAABJRU5ErkJggg==",
  "base64",
)

async function offer(page: Page, listingId: string, quantity: string, delivery: string) {
  await page.goto(`/listings/${listingId}`)
  await page.getByRole("link", { name: "Gửi đề nghị" }).click()
  await page.waitForURL(/\/contracts\/new/)
  await page.getByLabel("Khối lượng").first().fill(quantity)
  await page.getByLabel("Ngày giao dự kiến").first().fill(delivery)
  await page.getByRole("button", { name: "Gửi đề nghị" }).click()
  await page.waitForURL(/\/contracts\/[0-9a-f-]{36}$/)
  await expect(page.getByRole("heading", { name: "Chi tiết hợp đồng" })).toBeVisible()
  return contractIdOf(page)
}

/** waits until the listing page shows this available quantity (the reservation changes by event, not at once) */
async function expectAvailable(page: Page, listingId: string, quantity: string) {
  await expect(async () => {
    await page.goto(`/listings/${listingId}`)
    await expect(page.getByText(new RegExp(`Khối lượng khả dụng\\s*${quantity}`))).toBeVisible({ timeout: 3000 })
  }).toPass({ timeout: 60_000, intervals: [2000] })
}

async function sign(page: Page, email: string, contractId: string) {
  const since = new Date(Date.now() - 2000)
  const panel = page.getByRole("region", { name: "Ký hợp đồng" })
  await panel.getByRole("checkbox").check()
  await panel.getByRole("button", { name: "Gửi mã OTP" }).click()
  const message = await mail(email, /contract signing code/, since)
  const body = decodeMail(message!.Content.Body)
  expect(body).toContain(contractId)
  const code = body.match(/\b\d{6}\b/)![0]
  await panel.getByRole("group", { name: "Nhập mã OTP" }).getByRole("textbox").first().click()
  await page.keyboard.type(code)
  await panel.getByRole("button", { name: "Xác nhận chữ ký" }).click()
}

/** waits until the milestone page shows this status label (settlement and escrow move by event) */
async function expectMilestoneStatus(page: Page, url: string, label: string) {
  await expect(async () => {
    await page.goto(url)
    await expect(page.getByText(label, { exact: true }).first()).toBeVisible({ timeout: 3000 })
  }).toPass({ timeout: 90_000, intervals: [3000] })
}

/** one side weighs: weight, photo (waits for the virus scan), review, operational consent, submit */
async function weigh(page: Page, side: "SELLER" | "BUYER", weight: string, submit: string) {
  await page.locator(`#${side}-weight`).fill(weight)
  await page.locator(`#${side}-photo`).setInputFiles({ name: `${side.toLowerCase()}-weigh.png`, mimeType: "image/png", buffer: PIXEL_PNG })
  await expect(page.getByText("Sẵn sàng").first()).toBeVisible({ timeout: 60_000 })
  await page.getByRole("button", { name: "Xem lại trước khi gửi" }).click()
  await page.getByRole("checkbox").first().check()
  await page.getByRole("button", { name: submit }).click()
}

test.describe.serial("Golden flow: BF-03 signing and activation, then BF-04 delivery and settlement", () => {
  let buyer: Page
  let seller: Page
  let listingId: string
  let contractId: string
  let milestoneUrl: string
  // funding date = delivery minus the lead = today, so the milestone is funded at activation (D63, D67)
  const delivery = businessDaysAfter(todayIct(), FUNDING_LEAD)

  test.beforeAll(async ({ browser }) => {
    listingId = (await coffeeListing()).listingId
    buyer = await session(browser, "buyer1")
    seller = await session(browser, "seller1")
  })

  test("ST-BF03-010 Buyer sends an offer from the listing page", async () => {
    contractId = await offer(buyer, listingId, "1000", delivery)
    await expect(buyer.getByText("Đã gửi đề nghị").first()).toBeVisible()
  })

  test("ST-BF03-020 The offered quantity is reserved on the listing", async () => {
    await expectAvailable(buyer, listingId, "4.000 kg")
  })

  test("ST-BF03-030 Seller revises the unit price; the terms fingerprint changes", async () => {
    await seller.goto(`/contracts/${contractId}`)
    await expect(seller.getByText(/[0-9a-f]{8}…[0-9a-f]{8}/).first()).toBeVisible()
    const fingerprint = async () => (await seller.getByRole("main").innerText()).match(/[0-9a-f]{8}…[0-9a-f]{8}/)![0]
    const before = await fingerprint()
    await seller.getByLabel("Đơn giá đề xuất (VND/kg)").fill("95000.00")
    await seller.getByRole("button", { name: "Xem lại thay đổi" }).click()
    await seller.getByRole("button", { name: "Lưu điều khoản" }).click()
    await expect(seller.getByText("Đã lưu điều khoản mới")).toBeVisible()
    await expect(seller.getByText("95.000 ₫").first()).toBeVisible()
    await expect.poll(fingerprint, { timeout: 20_000 }).not.toBe(before)
  })

  test("ST-BF03-040 Buyer signs with the OTP from the mail", async () => {
    await buyer.goto(`/contracts/${contractId}`)
    await sign(buyer, "buyer1@test.com", contractId)
    await expect(buyer.getByText("Máy chủ đã xác nhận chữ ký của bạn", { exact: false })).toBeVisible({ timeout: 30_000 })
  })

  test("ST-BF03-050 Seller signs with the OTP; the contract is SIGNED", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    await seller.goto(`/contracts/${contractId}`)
    await sign(seller, "seller1@test.com", contractId)
    await expect(seller.getByText("Máy chủ đã xác nhận chữ ký của bạn", { exact: false })).toBeVisible({ timeout: 30_000 })
  })

  test("ST-BF03-060 Escrow locks both deposits; both parties see the contract ACTIVE", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    await expectStatus(buyer, "Đang hiệu lực")
    await seller.goto(`/contracts/${contractId}`)
    await expect(seller.getByText("Đang hiệu lực").first()).toBeVisible()
  })

  test("ST-BF04-010 Both parties see the funded milestone of the ACTIVE contract", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    const contract = (await api("buyer1", "GET", `/api/v1/contracts/${contractId}`, undefined, 200)).data
    expect(contract.milestones).toHaveLength(1)
    milestoneUrl = `/contracts/${contractId}/milestones/${contract.milestones[0].milestoneId}`
    await buyer.goto(`/contracts/${contractId}`)
    await expect(buyer.getByText("Đợt 1").first()).toBeVisible()
    await expect(buyer.getByText(/Đã khoá tiền đợt|Đang chuẩn bị hàng/).first()).toBeVisible()
  })

  test("ST-BF04-020 Seller weighs before dispatch with a photo; the milestone is SELLER_WEIGHED", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    await seller.goto(milestoneUrl)
    await expect(seller.getByText("Cân hàng trước khi xe chạy").first()).toBeVisible()
    await weigh(seller, "SELLER", "1000", "Xác nhận đã cân")
    await expectMilestoneStatus(seller, milestoneUrl, "Đã cân, đang vận chuyển")
  })

  test("ST-BF04-030 Buyer sees the seller's weight and records the received weight", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    await buyer.goto(milestoneUrl)
    await expect(buyer.getByText("Đã cân, đang vận chuyển").first()).toBeVisible()
    await expect(buyer.getByText("1.000").first()).toBeVisible()
    await weigh(buyer, "BUYER", "998", "Ghi nhận cân nhận hàng")
    await expectMilestoneStatus(buyer, milestoneUrl, "Đã nhận, chờ xác nhận")
  })

  test("ST-BF04-040 Buyer confirms a clean receipt; the milestone settles", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    await buyer.goto(milestoneUrl)
    await buyer.getByRole("button", { name: /Hàng đạt, xác nhận đúng/ }).click()
    await buyer.getByRole("checkbox").first().check()
    await buyer.getByRole("button", { name: "Xác nhận đúng và tất toán" }).click()
    await expectMilestoneStatus(buyer, milestoneUrl, "Đã tất toán")
  })

  test("ST-BF04-050 With its only milestone settled, the contract is SETTLED once escrow clears the locks", async () => {
    test.skip(!!NOT_A_BUSINESS_DAY, NOT_A_BUSINESS_DAY)
    // D94: completion decision -> escrow returns the deposits -> contract_lock_cleared -> contract.settled
    await expectStatus(buyer, "Đã tất toán")
    await seller.goto(`/contracts/${contractId}`)
    await expect(seller.getByText("Đã tất toán").first()).toBeVisible()
  })
})

test.describe.serial("BF-03 offer withdrawn before the second signature", () => {
  let buyer: Page
  let listingId: string
  let contractId: string

  test.beforeAll(async ({ browser }) => {
    listingId = (await coffeeListing()).listingId
    buyer = await session(browser, "buyer1")
  })

  test("ST-BF03-110 Buyer sends an offer", async () => {
    contractId = await offer(buyer, listingId, "1000", businessDaysAfter(todayIct(), FUNDING_LEAD + 5))
    await expectAvailable(buyer, listingId, "4.000 kg")
  })

  test("ST-BF03-120 Buyer withdraws the offer after confirming", async () => {
    await buyer.goto(`/contracts/${contractId}`)
    await buyer.getByRole("button", { name: "Rút đề nghị" }).first().click()
    const dialog = buyer.getByRole("dialog", { name: "Rút đề nghị hợp đồng?" })
    await dialog.getByRole("button", { name: "Rút đề nghị" }).click()
    await expect(buyer.getByText("Đã rút đề nghị").first()).toBeVisible({ timeout: 20_000 })
  })

  test("ST-BF03-130 The reserved quantity returns to the listing and no money stays locked", async () => {
    await expectAvailable(buyer, listingId, "5.000 kg")
    const contract = (await api("buyer1", "GET", `/api/v1/contracts/${contractId}`, undefined, 200)).data
    expect(contract.status).toBe("WITHDRAWN")
  })
})
