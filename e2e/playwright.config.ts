import { defineConfig, devices } from "@playwright/test";

const isWindows = process.platform === "win32";
const gradle = isWindows ? ".\\gradlew.bat" : "./gradlew";
const pnpm = "npx --yes pnpm@10.34.7";

/**
 * End-to-end tests run against the real stack: the browser talks to the Vite dev server, which proxies
 * to the gateway, which routes to identity (Postgres + Kafka). Infrastructure must already be running:
 *
 *     docker compose -f infra/docker-compose.yml up -d
 *
 * The three application servers are started here and reused if they are already up.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : [["list"]],
  outputDir: "./test-results",
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `${gradle} :services:identity:bootRun`,
      cwd: "../backend",
      url: "http://localhost:8081/actuator/health",
      timeout: 240_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `${gradle} :services:gateway:bootRun`,
      cwd: "../backend",
      url: "http://localhost:8080/actuator/health",
      timeout: 240_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `${pnpm} --filter @cutpay/dashboard dev`,
      cwd: "../frontend",
      url: "http://localhost:5173",
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
