// BF-01 User Onboarding & KYC through the web client: a Guest registers on the Keycloak page, completes the
// organization profile, verifies the e-mail with the code from the mail, and sees the Operator's KYC decision.
// The Operator's decision itself goes through the API: the web client has no KYC review screen yet.
import { expect, test, type Page } from "@playwright/test"
import { api, decodeMail, mail } from "./api"
import { accountId, register, submitProfile, verifyEmail } from "./fixtures"

test.describe.serial("BF-01 buyer onboarding approved", () => {
  const email = `st-buyer-${Date.now()}@test.com`
  let page: Page
  let since: Date

  test.beforeAll(async ({ browser }) => {
    page = await (await browser.newContext()).newPage()
  })

  test("ST-BF01-010 Guest registers as a buyer on the sign-up page", async () => {
    since = new Date(Date.now() - 5000)
    await register(page, email, "Người mua")
    await expect(page.getByRole("heading", { name: "Hoàn tất hồ sơ doanh nghiệp" })).toBeVisible()
    await expect(page.getByRole("radio", { name: "Bên mua" })).toBeChecked()
  })

  test("ST-BF01-020 Buyer submits the organization profile and waits for KYC", async () => {
    await submitProfile(page, "Cong ty ST Mua")
    await expect(page.getByText("Hồ sơ đang chờ Operator xác minh", { exact: false })).toBeVisible()
    await expect(page.getByText("Chờ xác minh").first()).toBeVisible()
  })

  test("ST-BF01-030 Buyer verifies the OTP e-mail with the code from the mail", async () => {
    await verifyEmail(page, email, since)
    const section = page.locator("section, div").filter({ has: page.getByRole("heading", { name: "Email nhận OTP" }) }).last()
    await expect(section.getByText("Đã xác minh")).toBeVisible({ timeout: 20_000 })
  })

  test("ST-BF01-040 Operator verifies the KYC (API: no review screen yet); the buyer sees VERIFIED", async () => {
    const id = await accountId(email)
    await api("operator1", "POST", `/api/v1/admin/users/${id}/verify`, { authorizationExpiresAt: null }, 200)
    await expect(async () => {
      await page.reload()
      await expect(page.getByText("Hồ sơ đang chờ Operator xác minh", { exact: false })).toBeHidden({ timeout: 2000 })
      await expect(page.getByRole("definition").filter({ hasText: "Đã xác minh" })).toBeVisible({ timeout: 2000 })
    }).toPass({ timeout: 60_000, intervals: [3000] })
  })

  test("ST-BF01-050 The KYC result reaches the buyer by e-mail", async () => {
    const message = await mail(email, /./, since)
    expect(message).toBeTruthy()
    const res = await fetch(`http://localhost:8025/api/v2/search?kind=to&query=${encodeURIComponent(email)}&limit=20`)
    const subjects = ((await res.json()).items as { Content: { Headers: { Subject: string[] } } }[])
      .map((m) => m.Content.Headers.Subject[0])
    expect(subjects.some((s) => !/email verification code/.test(s))).toBe(true)
  })
})

test.describe.serial("BF-01 seller onboarding rejected", () => {
  const email = `st-seller-${Date.now()}@test.com`
  let page: Page
  let since: Date

  test.beforeAll(async ({ browser }) => {
    page = await (await browser.newContext()).newPage()
  })

  test("ST-BF01-060 Guest registers as a seller and submits the profile", async () => {
    since = new Date(Date.now() - 5000)
    await register(page, email, "Người bán")
    await expect(page.getByRole("radio", { name: "Bên bán" })).toBeChecked()
    await submitProfile(page, "Cong ty ST Ban")
    await verifyEmail(page, email, since)
    await expect(page.getByText("Chờ xác minh").first()).toBeVisible()
  })

  test("ST-BF01-070 Operator rejects the KYC with a reason (API); the seller sees REJECTED, the reason comes by e-mail", async () => {
    const id = await accountId(email)
    const reason = "Thiếu giấy phép kinh doanh"
    const sent = new Date(Date.now() - 2000)
    await api("operator1", "POST", `/api/v1/admin/users/${id}/reject`, { reason }, 200)
    await expect(async () => {
      await page.reload()
      await expect(page.getByText("Hồ sơ chưa được chấp thuận", { exact: false })).toBeVisible({ timeout: 2000 })
    }).toPass({ timeout: 60_000, intervals: [3000] })
    await expect(page.getByRole("definition").filter({ hasText: "Chưa được chấp thuận" })).toBeVisible()
    // SRS BF-01: the result is sent by e-mail; the screen only states that the profile was not accepted
    const message = await mail(email, /^(?!.*email verification code)/i, sent)
    expect(decodeMail(message!.Content.Body)).toContain(reason)
  })
})
