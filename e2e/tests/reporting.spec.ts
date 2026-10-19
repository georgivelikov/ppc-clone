import { expect, test, type Page } from "@playwright/test";
import { nav, signUp, startCheckout } from "./support";

async function payWithCard(page: Page, checkout: Page) {
  const form = checkout.getByRole("form", { name: "Payment details" });
  await form.getByLabel("Email").fill("anna@example.com");
  await form.getByLabel("Card number").fill("4242 4242 4242 4242");
  await form.getByLabel("Expiry").fill("1299");
  await form.getByLabel("CVC").fill("123");
  await form.getByRole("button", { name: /^Pay/ }).click();
  await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });
  await page.getByRole("dialog").getByRole("button", { name: "Close", exact: true }).first().click();
}

test.describe("reports and insights", () => {
  test("a payment shows up in the numbers, and a monthly statement downloads with the ledger's totals", async ({ page, context }) => {
    test.setTimeout(180_000);
    await signUp(page);
    await payWithCard(page, await startCheckout(page, context, "25.00"));

    await nav(page).getByRole("link", { name: "Reports" }).click();
    // The read model is a few seconds behind the payment.
    await expect(async () => {
      await page.reload();
      await expect(page.getByTestId("funnel-paid")).toHaveText("1", { timeout: 2_000 });
      await expect(page.getByTestId("insights-money-EUR")).toContainText("€25.00", { timeout: 2_000 });
    }).toPass({ timeout: 60_000, intervals: [1_000, 2_000, 3_000] });
    await expect(page.getByTestId("success-rate")).toHaveText("100%");
    await expect(page.getByTestId("funnel-opened")).toHaveText("1");
    await expect(page.getByRole("list", { name: "Payment methods" })).toContainText("Card");

    // A monthly statement as CSV.
    await page.getByLabel("Report", { exact: true }).selectOption("monthly_statement");
    await page.getByRole("button", { name: "Make the report" }).click();
    const run = page.getByTestId("report-run").first();
    await expect(run.getByTestId("report-status")).toHaveText("succeeded", { timeout: 30_000 });
    const href = await run.getByRole("link", { name: "Download" }).getAttribute("href");
    const csv = await (await page.request.get(href!)).text();
    expect(csv).toContain("Month,Currency,Sales,Refunds,Fees,Disputes,Payouts,Net change");
    // €25.00 less 1.29% + 0.10 is 24.58 net.
    expect(csv).toMatch(/,EUR,25\.00,0\.00,0\.42,0\.00,0\.00,24\.58/);

    // A link whose signature was changed does not work.
    const tampered = await page.request.get(href!.replace(/signature=[0-9a-f]/, "signature=0"));
    expect(tampered.status()).toBe(403);

    // The same report as an Excel file.
    await page.getByLabel("File").selectOption("xlsx");
    await page.getByRole("button", { name: "Make the report" }).click();
    await expect(page.getByTestId("report-run").filter({ hasText: "XLSX" }).getByTestId("report-status")).toHaveText("succeeded", { timeout: 30_000 });
    const xlsx = page.getByTestId("report-run").filter({ hasText: "XLSX" }).getByRole("link", { name: "Download" });
    const bytes = await (await page.request.get((await xlsx.getAttribute("href"))!)).body();
    expect(bytes.subarray(0, 2).toString()).toBe("PK");

    // Home shows the same numbers.
    await nav(page).getByRole("link", { name: "Home" }).click();
    await expect(page.getByTestId("insights")).toContainText("€25.00");
  });
});
