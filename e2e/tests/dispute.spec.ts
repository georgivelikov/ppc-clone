import { expect, test, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";
import { nav, signUp, startCheckout } from "./support";

/** Pays with the test card whose holder disputes the payment a day later, and returns to the dashboard. */
async function payWithDisputedCard(page: Page, context: BrowserContext, amount: string) {
  const checkout = await startCheckout(page, context, amount);
  const form = checkout.getByRole("form", { name: "Payment details" });
  await form.getByLabel("Email").fill("anna@example.com");
  await form.getByLabel("Card number").fill("4000 0000 0000 0259");
  await form.getByLabel("Expiry").fill("1299");
  await form.getByLabel("CVC").fill("123");
  await form.getByRole("button", { name: /^Pay/ }).click();
  await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });
  await page.getByRole("dialog").getByRole("button", { name: "Close", exact: true }).first().click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}

/** A day passes for the payments and ledger services (their clocks are simulated). */
async function nextDay(request: APIRequestContext) {
  for (const port of [8082, 8084]) {
    const res = await request.post(`http://localhost:${port}/admin/clock/advance?days=1`);
    expect(res.ok()).toBeTruthy();
  }
}

async function openDisputes(page: Page) {
  await nav(page).getByRole("link", { name: "Disputes" }).click();
  await expect(page.getByRole("heading", { name: "Disputes" })).toBeVisible();
}

/** Reloads until the job has opened the dispute, which takes a few seconds after the day passes. */
async function waitForDispute(page: Page, count: number) {
  await expect(async () => {
    await page.reload();
    await expect(page.getByTestId("dispute-row")).toHaveCount(count, { timeout: 2_000 });
  }).toPass({ timeout: 45_000, intervals: [1_000, 2_000, 3_000] });
}

test.describe("disputes", () => {
  test("a disputed payment is answered with evidence, won, and the money and fee come back", async ({ page, context, request }) => {
    test.setTimeout(240_000);
    await signUp(page);
    await payWithDisputedCard(page, context, "40.00");
    await nextDay(request);

    await openDisputes(page);
    await waitForDispute(page, 1);
    await expect(page.getByTestId("dispute-row")).toContainText("Needs response");
    await expect(page.getByTestId("dispute-row")).toContainText("€40.00");
    await page.getByTestId("dispute-row").click();

    await expect(page.getByTestId("dispute-amount")).toHaveText("€40.00");
    await expect(page.getByText("Dispute fee").locator("..")).toContainText("€15.00");

    // Evidence that does not convince the network would lose; this one carries the words the simulator looks for.
    await page.getByLabel("What happened").fill("The parcel was signed for on 3 October. winning_evidence");
    await page.getByLabel("Attach a file").setInputFiles({ name: "delivery.txt", mimeType: "text/plain", buffer: Buffer.from("Signed by A. Ivanova") });
    await expect(page.getByTestId("dispute-file")).toContainText("delivery.txt");
    await page.getByRole("button", { name: "Send evidence" }).click();
    await expect(page.getByTestId("dispute-status")).toHaveText("Under review");
    await expect(page.getByTestId("dispute-status")).toHaveText("Won", { timeout: 30_000 });
    await expect(page.getByTestId("dispute-outcome")).toContainText("You won");

    // The balance shows it being taken and given back, fee included.
    await nav(page).getByRole("link", { name: "Balances" }).click();
    await expect(async () => {
      await page.reload();
      const rows = page.getByRole("row");
      await expect(rows.filter({ hasText: "Dispute won" })).toHaveCount(1, { timeout: 2_000 });
      await expect(rows.filter({ hasText: "Dispute fee returned" })).toHaveCount(1, { timeout: 2_000 });
    }).toPass({ timeout: 45_000, intervals: [1_000, 2_000, 3_000] });
    // Four lines: the amount and the fee taken, then both given back.
    await expect(page.getByRole("row").filter({ hasText: "Dispute" })).toHaveCount(4);

    // The books still balance.
    const trial = await (await request.get("http://localhost:8084/admin/trial-balance")).json();
    expect(trial.balanced).toBe(true);
  });

  test("accepting a dispute loses it, and an open one blocks the refund", async ({ page, context, request }) => {
    test.setTimeout(240_000);
    await signUp(page);
    await payWithDisputedCard(page, context, "25.00");
    await nextDay(request);

    await openDisputes(page);
    await waitForDispute(page, 1);
    await page.getByTestId("dispute-row").click();

    // The payment cannot be refunded while the money is held.
    await page.getByRole("link", { name: /^pay_/ }).click();
    await page.getByRole("button", { name: "Refund" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Refund" }).click();
    await expect(page.getByRole("dialog").getByRole("alert")).toContainText("being disputed");
    await page.getByRole("dialog").getByRole("button", { name: "Close", exact: true }).first().click();

    await openDisputes(page);
    await page.getByTestId("dispute-row").click();
    await page.getByRole("button", { name: "Accept the dispute" }).click();
    await expect(page.getByTestId("dispute-status")).toHaveText("Lost");
    await expect(page.getByTestId("dispute-outcome")).toContainText("You lost");

    // Settled for good, so nothing more can be sent.
    await expect(page.getByRole("button", { name: "Send evidence" })).toHaveCount(0);
  });
});
