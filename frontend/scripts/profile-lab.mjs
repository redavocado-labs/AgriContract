import { chromium } from '@playwright/test'

const base = 'http://127.0.0.1:4181'
const profiles = [
  { name: 'desktop', viewport: { width: 1440, height: 900 }, latency: 20, mbps: 20, cpu: 1 },
  { name: 'mobile', viewport: { width: 390, height: 844 }, latency: 150, mbps: 1.6, cpu: 4 },
]
const paths = ['/', '/listing', '/prices']
const browser = await chromium.launch()
try {
  for (const profile of profiles) {
    for (const path of paths) {
      const measurements = []
      for (let run = 0; run < 3; run++) {
        const page = await browser.newPage({ viewport: profile.viewport, isMobile: profile.name === 'mobile', deviceScaleFactor: 1, hasTouch: profile.name === 'mobile' })
        try {
          await page.route('**/*', (route) => {
            const url = new URL(route.request().url())
            return url.origin === base && !/^\/(api|realms)\//.test(url.pathname)
              ? route.continue()
              : route.abort()
          })
          const session = await page.context().newCDPSession(page)
          await session.send('Network.enable')
          await session.send('Network.emulateNetworkConditions', {
            offline: false,
            latency: profile.latency,
            downloadThroughput: profile.mbps * 125_000,
            uploadThroughput: profile.mbps * 125_000,
          })
          await session.send('Emulation.setCPUThrottlingRate', { rate: profile.cpu })
          await page.addInitScript(() => {
            const metrics = { lcp: 0, cls: 0 }
            new PerformanceObserver((list) => {
              for (const entry of list.getEntries()) metrics.lcp = entry.startTime
            }).observe({ type: 'largest-contentful-paint', buffered: true })
            new PerformanceObserver((list) => {
              for (const entry of list.getEntries()) if (!entry.hadRecentInput) metrics.cls += entry.value
            }).observe({ type: 'layout-shift', buffered: true })
            window.labMetrics = metrics
          })
          await page.goto(`${base}${path}`, { waitUntil: 'networkidle', timeout: 30_000 })
          await page.locator('#root > div').first().waitFor({ timeout: 10_000 })
          measurements.push(await page.evaluate(() => ({
            lcp: Math.round(window.labMetrics.lcp),
            cls: Number(window.labMetrics.cls.toFixed(3)),
            horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
            jsBytes: performance.getEntriesByType('resource')
              .filter((entry) => entry.name.endsWith('.js'))
              .reduce((sum, entry) => sum + entry.encodedBodySize, 0),
          })))
        } finally {
          await page.close()
        }
      }
      console.log(JSON.stringify({ profile: profile.name, path, measurements }))
    }
  }
} finally {
  await browser.close()
}
