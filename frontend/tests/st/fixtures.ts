import { expect, type Browser, type Page } from "@playwright/test"
import { PASSWORD } from "./api"

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
