import { defineConfig } from "@playwright/test"

export default defineConfig({
  testDir: ".",
  testMatch: "**/*.pw.ts",
  timeout: 30_000,
  workers: 1,
  reporter: "list",
  use: {
    browserName: "chromium",
    serviceWorkers: "block",
    actionTimeout: 6000,
  },
})
