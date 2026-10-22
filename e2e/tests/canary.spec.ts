import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { signUp, startCheckout } from "./support";

/**
 * The canary: one customer journey, run the way a person would, on a schedule, against a live environment. It
 * answers "can someone sign up, take a payment and see it?" - which a green dashboard of internal metrics cannot.
 *
 *   npx playwright test -c canary/playwright.config.ts          (against DASHBOARD_URL; see e2e/canary/README.md)
 *   npx playwright test --grep @canary                          (against the local stack, as part of the suite)
 *
 * It writes canary-result.json (and, when PUSHGATEWAY_URL is set, a Prometheus metric) so a scheduler can alert on it.
 */
test.describe("@canary", () => {
  test("a new merchant takes a card payment and sees it", async ({ page, context }, info) => {
    test.setTimeout(90_000);
    const started = Date.now();
    let stage = "sign-up";
    try {
      await signUp(page);
      stage = "checkout";
      const checkout = await startCheckout(page, context, "5.00");
      stage = "payment";
      await checkout.getByRole("form", { name: "Payment details" }).getByLabel("Email").fill("canary@example.com");
      await checkout.getByLabel("Card number").fill("4242 4242 4242 4242");
      await checkout.getByLabel("Expiry").fill("1299");
      await checkout.getByLabel("CVC").fill("123");
      const payStarted = Date.now();
      await checkout.getByRole("button", { name: /^Pay/ }).click();
      await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 15_000 });
      const payMs = Date.now() - payStarted;
      stage = "dashboard";
      await page.getByRole("link", { name: "View payment" }).click();
      await expect(page.getByText("Succeeded", { exact: true })).toBeVisible({ timeout: 20_000 });
      // A budget, not just a pass/fail: a payment that takes 12 seconds "works" and is still an incident.
      expect(payMs, "time from Pay to success").toBeLessThan(8_000);
      await report(info.project.name, true, "", Date.now() - started, payMs);
    } catch (error) {
      await report(info.project.name, false, stage, Date.now() - started, 0);
      throw error;
    }
  });
});

async function report(project: string, ok: boolean, failedStage: string, totalMs: number, payMs: number) {
  writeFileSync("canary-result.json", JSON.stringify({ project, ok, failedStage, totalMs, payMs, at: new Date().toISOString() }, null, 2));
  const push = process.env.PUSHGATEWAY_URL;
  if (!push) return;
  const body = [
    `cutpay_canary_success ${ok ? 1 : 0}`,
    `cutpay_canary_duration_seconds ${totalMs / 1000}`,
    `cutpay_canary_payment_seconds ${payMs / 1000}`,
    `cutpay_canary_last_run_timestamp_seconds ${Math.floor(Date.now() / 1000)}`,
    "",
  ].join("\n");
  await fetch(`${push.replace(/\/$/, "")}/metrics/job/cutpay_canary`, { method: "PUT", body }).catch(() => undefined);
}
