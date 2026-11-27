// BF-03 Contract Signing & Activation through the web client: the buyer makes an offer from the listing page, the
// seller revises the price, both sign with the OTP from their mail, the contract turns ACTIVE once escrow locks the
// deposits. A second offer is withdrawn before any signature. The seller's listing is created through the API.
import { expect, test, type Page } from "@playwright/test"
import { api, coffeeListing, decodeMail, mail } from "./api"
import { FUNDING_LEAD, NOT_A_BUSINESS_DAY, businessDaysAfter, contractIdOf, expectStatus, session, todayIct } from "./fixtures"

export const shared: { contractId?: string; listingId?: string } = {}

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

test.describe.serial("BF-03 offer, negotiation, signing, activation", () => {
  let buyer: Page
  let seller: Page
  let listingId: string
  let contractId: string
  // funding date = delivery minus the lead = today, so the milestone is funded at activation (D63, D67)
  const delivery = businessDaysAfter(todayIct(), FUNDING_LEAD)

  test.beforeAll(async ({ browser }) => {
    listingId = (await coffeeListing()).listingId
    shared.listingId = listingId
    buyer = await session(browser, "buyer1")
    seller = await session(browser, "seller1")
  })

  test("ST-BF03-010 Buyer sends an offer from the listing page", async () => {
    contractId = await offer(buyer, listingId, "1000", delivery)
    shared.contractId = contractId
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
