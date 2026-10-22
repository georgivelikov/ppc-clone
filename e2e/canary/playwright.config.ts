import { defineConfig, devices } from "@playwright/test";

/**
 * Runs the @canary journey against a running environment (nothing is started here). DASHBOARD_URL is where merchants sign in;
 * the dashboard opens the hosted checkout in a new tab on whatever address the payments service was told to use.
 *
 *   DASHBOARD_URL=https://dashboard.example.com npx playwright test -c canary/playwright.config.ts
 */
export default defineConfig({
  testDir: "../tests",
  grep: /@canary/,
  retries: 1, // one retry: a single blip is not an incident, two in a row is
  timeout: 90_000,
  reporter: [["list"]],
  outputDir: "../test-results/canary",
  use: {
    baseURL: process.env.DASHBOARD_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: process.env.CANARY_NAME ?? "canary", use: { ...devices["Desktop Chrome"] } }],
});
