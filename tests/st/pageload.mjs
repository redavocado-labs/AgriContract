// Page load times of the public screens for Report 5.4 (SRS IV.2.2: a screen within 3 s), measured while
// scripts/unit-test-report/perf_nfr.py puts the concurrent-user load on the same stack. Each page is opened N times in
// a fresh Chromium context; "load" is the load event, "idle" the moment Playwright sees no network for 500 ms (so the
// whole screen has its data; fill_54_nfr.py subtracts those 500 ms).
//
//   node pageload.mjs <out.json> [times=5]      (frontend on ST_BASE_URL, default http://localhost:5173)
import { writeFileSync } from "node:fs"
import { chromium } from "@playwright/test"

const [out, times = "5"] = process.argv.slice(2)
if (!out) throw new Error("usage: node pageload.mjs <out.json> [times]")
const base = process.env.ST_BASE_URL ?? "http://localhost:5173"
const paths = ["/", "/listing", "/prices"]

const browser = await chromium.launch()
const result = {}
for (const path of paths) {
  result[path] = []
  for (let i = 0; i < Number(times); i++) {
    const context = await browser.newContext({ viewport: { width: 1366, height: 900 }, locale: "vi-VN" })
    const page = await context.newPage()
    const start = Date.now()
    await page.goto(base + path, { waitUntil: "load" })
    const load = Date.now() - start
    await page.waitForLoadState("networkidle")
    result[path].push({ load, idle: Date.now() - start })
    await context.close()
  }
}
await browser.close()
writeFileSync(out, JSON.stringify(result))
console.log(JSON.stringify(result))
