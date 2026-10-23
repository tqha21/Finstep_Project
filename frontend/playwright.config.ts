import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests", fullyParallel: false, workers: 1, timeout: 90_000,
  expect: { timeout: 15_000 }, reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:3100", viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure", trace: "retain-on-failure",
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {},
  },
  webServer: { command: "npm run dev -- --port 3100", url: "http://127.0.0.1:3100", reuseExistingServer: false, timeout: 120_000,
    env: { API_ORIGIN: process.env.API_ORIGIN ?? "http://127.0.0.1:8080", NEXT_TELEMETRY_DISABLED: "1" } },
});
