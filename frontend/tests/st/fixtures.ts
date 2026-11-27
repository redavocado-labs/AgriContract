import { expect, type Browser, type Page } from "@playwright/test"
import { PASSWORD, decodeMail, mail, token } from "./api"

/** Signs in through the real Keycloak login page, as a user does, and lands back in the web client. */
export async function signIn(page: Page, username: string, password = PASSWORD) {
  await page.goto("/login")
  await page.getByRole("button", { name: "Đăng nhập" }).click()
  await page.waitForURL(/\/realms\/agricontract\//)
  await page.getByLabel(/username|email|tên đăng nhập/i).first().fill(username)
  await page.getByLabel(/password|mật khẩu/i).first().fill(password)
  await page.keyboard.press("Enter")
  await page.waitForURL((url) => !url.href.includes("/realms/"))
  await page.waitForLoadState("networkidle")
}

export async function session(browser: Browser, username: string, password = PASSWORD) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await signIn(page, username, password)
  return page
}

const DAY = 86_400_000

/** Today in Vietnam, YYYY-MM-DD. */
export function todayIct() {
  return new Date(Date.now() + 7 * 3_600_000).toISOString().slice(0, 10)
}

const holidays = new Set((process.env.VIETNAM_HOLIDAYS ?? "").split(",").filter(Boolean))
export function isBusinessDay(iso: string) {
  const day = new Date(`${iso}T00:00:00Z`).getUTCDay()
  return day !== 0 && day !== 6 && !holidays.has(iso)
}

export function businessDaysAfter(from: string, n: number) {
  let date = new Date(`${from}T00:00:00Z`)
  for (let left = n; left > 0;) {
    date = new Date(date.getTime() + DAY)
    if (isBusinessDay(date.toISOString().slice(0, 10))) left--
  }
  return date.toISOString().slice(0, 10)
}

/** The offer form's default funding lead ("Khoá tiền đợt trước hạn giao"), in business days. */
export const FUNDING_LEAD = 7

/**
 * The signing -> activation -> milestone chain needs the funding date (delivery minus the lead) to be today:
 * D67 refuses the second signature when it has passed, D63 funds at activation only when it has come. A funding
 * date is a business day, so on any other day the chain cannot run and its cases are skipped (Pending).
 */
export const NOT_A_BUSINESS_DAY = isBusinessDay(todayIct())
  ? ""
  : `${todayIct()} is not a Vietnam business day: no milestone can be funded at activation today (D63, D67)`

export async function expectStatus(page: Page, label: string) {
  await expect(async () => {
    await page.reload()
    await expect(page.getByText(label, { exact: true }).first()).toBeVisible({ timeout: 3000 })
  }).toPass({ timeout: 90_000, intervals: [3000] })
}

export function contractIdOf(page: Page) {
  return page.url().match(/contracts\/([0-9a-f-]{36})/)?.[1] ?? ""
}

// ── onboarding (BF-01), shared with the usability cases ─────────────────────────────────────────────────────

/** password of the accounts the onboarding cases register (meets the realm policy) */
export const NEW_PASSWORD = "Agri#Contract2026x"

/** Guest signs up on the Keycloak page with an e-mail and a role, and lands on /onboarding. */
export async function register(page: Page, email: string, role: "Người mua" | "Người bán") {
  await page.goto("/register")
  await page.getByRole("button", { name: "Đăng ký" }).click()
  await page.waitForURL(/\/realms\/agricontract\//)
  await page.getByLabel("Email *").fill(email)
  await page.getByRole("radio", { name: role }).check()
  await page.getByLabel("Mật khẩu *", { exact: true }).fill(NEW_PASSWORD)
  await page.getByLabel("Nhập lại mật khẩu *").fill(NEW_PASSWORD)
  await page.getByRole("button", { name: "Tạo tài khoản" }).click()
  await page.waitForURL(/\/onboarding/)
}

export async function submitProfile(page: Page, name: string) {
  await page.getByLabel("Tên doanh nghiệp *").fill(name)
  await page.getByLabel("Số điện thoại").fill("0901000111")
  await page.getByLabel("Địa chỉ").fill("1 Le Loi, Buon Ma Thuot")
  await page.getByRole("button", { name: "Gửi hồ sơ doanh nghiệp" }).click()
  await page.waitForURL(/\/account/)
}

export async function verifyEmail(page: Page, email: string, since: Date) {
  const message = await mail(email, /email verification code/, since)
  const code = decodeMail(message!.Content.Body).match(/\b\d{6}\b/)![0]
  const boxes = page.getByRole("group", { name: "Mã xác minh email" }).getByRole("textbox")
  await boxes.first().click()
  await page.keyboard.type(code)
  await page.getByRole("button", { name: "Xác minh email" }).click()
}

export async function accountId(email: string) {
  const res = await fetch(`${process.env.ST_GATEWAY_URL ?? "http://localhost:8080"}/api/v1/users/me`, {
    headers: { authorization: `Bearer ${await token(email, NEW_PASSWORD)}` },
  })
  return (await res.json()).userId as string
}
