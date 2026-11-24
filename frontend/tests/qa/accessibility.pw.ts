import assert from "node:assert/strict"
import { AxeBuilder } from "@axe-core/playwright"
import { expect, type Page, type TestInfo } from "@playwright/test"
import { contractDto, contractId, contractPath, json, test } from "./browser-fixture"

async function expectPageReflow(page: Page) {
  const { viewport, content } = await page.evaluate(async () => {
    await document.fonts.ready
    return {
      viewport: document.documentElement.clientWidth,
      content: document.documentElement.scrollWidth,
    }
  })
  expect(viewport).toBe(320)
  expect(content).toBeLessThanOrEqual(viewport)
}

async function audit(page: Page, info: TestInfo) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze()
  const violations = result.violations.map(({ id, impact, nodes }) => ({
    id,
    impact,
    targets: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })),
  }))
  await info.attach("axe violations", {
    body: JSON.stringify(violations, null, 2),
    contentType: "application/json",
  })
  assert.deepEqual(
    violations.filter(({ impact }) => impact === "serious" || impact === "critical"),
    []
  )
}

test("FE-016 an offer withdrawal dialog can be opened, traversed and dismissed by keyboard", async ({ scenario, load }) => {
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("OFFERED"))
  })
  await load(page, `/contracts/${contractId}`)
  const trigger = page.getByRole("button", { name: "Rút đề nghị", exact: true })
  await expect(trigger).toBeVisible()
  for (let step = 0; step < 20; step++) {
    await page.keyboard.press("Tab")
    if (await trigger.evaluate((element) => element === document.activeElement)) break
  }
  await expect(trigger).toBeFocused()
  await page.keyboard.press("Enter")
  const dialog = page.getByRole("dialog", { name: "Rút đề nghị hợp đồng?" })
  await expect(dialog).toBeVisible()
  await expect.poll(() => dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true)
  const keep = dialog.getByRole("button", { name: "Giữ đề nghị", exact: true })
  const withdraw = dialog.getByRole("button", { name: "Rút đề nghị", exact: true })
  await keep.focus()
  await page.keyboard.press("Tab")
  await expect(withdraw).toBeFocused()
  await page.keyboard.press("Tab")
  await expect(keep).toBeFocused()
  await page.keyboard.press("Escape")
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
})

test("FE-016 contract detail with the withdrawal dialog open has no serious axe findings", async ({ scenario, load }, info) => {
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("OFFERED"))
  })
  await load(page, `/contracts/${contractId}`)
  await page.getByRole("button", { name: "Rút đề nghị", exact: true }).click()
  await expect(page.getByRole("dialog", { name: "Rút đề nghị hợp đồng?" })).toBeVisible()
  await audit(page, info)
})

test("FE-016 public prices expose a keyboard-accessible route without serious axe findings", async ({ scenario, load }, info) => {
  const priceBase = "/api/v1/prices/COFFEE"
  const page = await scenario((request, path) => {
    if (request.method() !== "GET") return
    assert.equal(request.headers()["authorization"], undefined)
    if (path === `${priceBase}/dak-lak/items`) return json(200, {
      commodity: "COFFEE",
      province: "dak-lak",
      items: [{ itemSlug: "ca-phe-nhan-xo", itemName: "Cà phê nhân xô" }],
    })
    if (path === `${priceBase}/dak-lak`) {
      assert.equal(new URL(request.url()).searchParams.get("item"), "ca-phe-nhan-xo")
      return json(200, {
        commodity: "COFFEE",
        itemSlug: "ca-phe-nhan-xo",
        itemName: "Cà phê nhân xô",
        province: "Đắk Lắk",
        provinceSlug: "dak-lak",
        price: "86500.00",
        capturedAt: "2026-11-23",
        ingestedAt: "2026-11-24T07:00:00Z",
        source: "VNSAT_SCRAPE",
        stale: false,
        referenceSource: null,
      })
    }
    if (path === `${priceBase}/lam-dong/items`)
      return json(200, { commodity: "COFFEE", province: "lam-dong", items: [] })
  }, { actor: "GUEST" })
  await load(page, "/prices")
  await expect(page.getByRole("heading", { name: "Bảng giá tham khảo" })).toBeVisible()
  await expect(page.getByText("86.500 ₫/kg")).toBeVisible()
  const province = page.getByRole("combobox", { name: "Tỉnh" })
  for (let step = 0; step < 20; step++) {
    await page.keyboard.press("Tab")
    if (await province.evaluate((element) => element === document.activeElement)) break
  }
  await expect(province).toBeFocused()
  await page.keyboard.press("ArrowDown")
  await expect(province).toHaveValue("lam-dong")
  await expect(page.getByText("Chưa có giá cho tỉnh này")).toBeVisible()
  await expect(page.getByText("86.500 ₫/kg")).toBeHidden()
  await page.keyboard.press("ArrowUp")
  await expect(page.getByText("86.500 ₫/kg")).toBeVisible()
  await audit(page, info)
  await page.setViewportSize({ width: 320, height: 800 })
  await expectPageReflow(page)
})

test("FE-016 the public home page reflows at 320 CSS pixels", async ({ scenario, load }) => {
  const page = await scenario(() => undefined, { actor: "GUEST" })
  await page.setViewportSize({ width: 320, height: 800 })
  await load(page, "/")
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible()
  await expectPageReflow(page)
})
