import { defineConfig, devices } from "@playwright/test"

// System tests through the web client against a real stack (Report 5.3): the Docker Compose stack of backend/
// (gateway on 8080, Keycloak on 8180, MailHog on 8025) and a production build of the frontend served on 5173:
//   VITE_API_BASE_URL=http://localhost:8080 VITE_USE_MOCKS=false npx vite build --outDir <dir> && npx vite preview --outDir <dir> --port 5173
// (a dev server reloads the page whenever a file under frontend/ changes, test artifacts included)
//   npx playwright test --config tests/st/playwright.st.config.ts
// Nothing is mocked. Each test is one ST case; the cases of a flow run in order and share its state.
export default defineConfig({
  testDir: ".",
  testMatch: "**/*.st.ts",
  timeout: 240_000,
  workers: 1,
  fullyParallel: false,
  reporter: [["list"], ["json", { outputFile: process.env.ST_JSON ?? "../../../scripts/unit-test-report/results/st-ui/results.json" }]],
  outputDir: process.env.ST_OUTPUT ?? "/tmp/agricontract-st-artifacts",
  use: {
    baseURL: process.env.ST_BASE_URL ?? "http://localhost:5173",
    actionTimeout: 20_000,
    navigationTimeout: 30_000,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "vi-VN",
    timezoneId: "Asia/Ho_Chi_Minh",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1366, height: 900 } } },
    // SRS IV.2.1 supported browsers: the public screens again on Firefox
    { name: "firefox", testMatch: "**/browsers.st.ts", use: { ...devices["Desktop Firefox"], viewport: { width: 1366, height: 900 } } },
  ],
})
