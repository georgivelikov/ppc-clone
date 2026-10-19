import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { nav, signUp, startCheckout } from "./support";

/** The ledger and payouts services run on a simulated clock; this moves both forward. */
async function advanceClocks(request: APIRequestContext, days: number) {
  for (const port of [8084, 8085]) {
    const res = await request.post(`http://localhost:${port}/admin/clock/advance?days=${days}`);
    expect(res.ok()).toBeTruthy();
  }
}

/** Pays a checkout with the test card and leaves the dashboard tab clear of the dialog that offered the link. */
async function payWithCard(page: Page, checkout: Page) {
  const form = checkout.getByRole("form", { name: "Payment details" });
  await form.getByLabel("Email").fill("anna@example.com");
  await form.getByLabel("Card number").fill("4242 4242 4242 4242");
  await form.getByLabel("Expiry").fill("1299");
  await form.getByLabel("CVC").fill("123");
  await form.getByRole("button", { name: /^Pay/ }).click();
  await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });
  await page.getByRole("dialog").getByRole("button", { name: "Close", exact: true }).first().click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}

/** Reloads the balances until the ledger has caught up, which takes a few seconds after a payment or a clock change. */
async function eventually(page: Page, check: () => Promise<void>, timeout = 45_000) {
  await expect(async () => {
    await page.reload();
    await page.waitForLoadState("networkidle");
    await check();
  }).toPass({ timeout, intervals: [1_000, 2_000, 3_000] });
}

test.describe("money in other currencies", () => {
  test("RON 500 paid to a merchant that settles in euros is converted with the markup, and the ledger stays balanced", async ({
    page,
    context,
    request,
  }) => {
    test.setTimeout(180_000);
    await signUp(page); // a Bulgarian merchant, which settles in euros

    // Ask for other currencies to be converted by themselves.
    await nav(page).getByRole("link", { name: "Balances" }).click();
    const settings = page.getByRole("form", { name: "Currency settings" });
    await expect(settings.getByRole("checkbox", { name: "EUR" })).toBeChecked();
    await settings.getByRole("checkbox", { name: "Convert other currencies automatically" }).check();
    await settings.getByRole("button", { name: "Save currencies" }).click();
    await expect(settings.getByRole("status")).toHaveText("Saved");
    await expect(settings.getByTestId("fx-markup")).toContainText("1% over the market rate");

    const checkout = await startCheckout(page, context, "500.00", "RON");
    await payWithCard(page, checkout);

    // The payment is booked in the currency it was paid in and waits there until it is available.
    await nav(page).getByRole("link", { name: "Balances" }).click();
    await eventually(page, async () => {
      await expect(page.getByTestId("balance-RON")).toContainText("RON 493.45 pending", { timeout: 2_000 });
    });

    await advanceClocks(request, 3);
    // Once available it is converted at the current rate, less the markup: about RON 493.45 / 4.97 = EUR 99.28, less 1%.
    await eventually(page, async () => {
      await expect(page.getByTestId("balance-available")).toHaveText(/^€(8[89]|9\d|10\d)\.\d\d$/, { timeout: 2_000 });
    });
    await expect(page.getByTestId("balance-pending")).toHaveText("€0.00");

    const report = page.getByRole("table", { name: "Conversion totals" });
    await expect(report.getByTestId("fx-report-EUR")).toContainText("1");
    const latest = page.getByRole("list", { name: "Latest conversions" }).getByTestId("fx-conversion");
    await expect(latest).toHaveCount(1);
    await expect(latest.first()).toContainText("RON 493.45 →");
    await expect(latest.first()).toContainText("automatic");
    // The Balances page lists both sides of it.
    await expect(page.getByRole("row", { name: /Conversion/ }).first()).toBeVisible();

    // Every currency of the ledger balances on its own.
    const trial = await (await request.get("http://localhost:8084/admin/trial-balance")).json();
    expect(trial.balanced).toBe(true);
    for (const currency of trial.by_currency as { currency: string; total: number }[]) expect(currency.total, currency.currency).toBe(0);
  });

  test("a merchant converts available money by hand: a quote locks the rate, then the conversion uses it", async ({
    page,
    context,
    request,
  }) => {
    test.setTimeout(180_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "200.00", "RON");
    await payWithCard(page, checkout);
    await advanceClocks(request, 3);

    await nav(page).getByRole("link", { name: "Balances" }).click();
    // RON 200.00 less 1.29% + 0.10 is RON 197.32.
    await eventually(page, async () => {
      await expect(page.getByTestId("balance-RON")).toContainText("RON 197.32 available", { timeout: 2_000 });
    });

    await page.getByRole("button", { name: "Convert RON" }).first().click();
    const dialog = page.getByRole("dialog", { name: "Convert RON" });
    await expect(dialog.getByLabel("Amount to convert")).toHaveValue("197.32");
    await expect(dialog.getByLabel("Into")).toHaveValue("EUR");
    await dialog.getByRole("button", { name: "Get a quote" }).click();

    await expect(dialog.getByTestId("quote-receive")).toHaveText(/^€(3[5-9]|4\d)\.\d\d$/);
    await expect(dialog.getByTestId("quote-timer")).toContainText(/held for 1[45]:\d\d/);
    await expect(dialog.getByText(/^Markup \(1%\)$/)).toBeVisible();
    const received = (await dialog.getByTestId("quote-receive").textContent())!;

    await dialog.getByRole("button", { name: "Convert", exact: true }).click();
    // The dialog now says what happened, under another title.
    const result = page.getByRole("dialog", { name: "Converted" });
    await expect(result.getByTestId("conversion-result")).toContainText("RON 197.32 became");
    await expect(result.getByTestId("conversion-result")).toContainText(received);
    await result.getByRole("button", { name: "Done" }).click();

    // The euros are in the main balance and the RON balance is gone.
    await expect(page.getByTestId("balance-available")).toHaveText(received);
    await expect(page.getByTestId("balance-RON")).toHaveCount(0);
  });
});
