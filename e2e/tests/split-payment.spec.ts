import { expect, test, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Delivery Hero");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Delivery Hero");
}

/** The ledger and payouts services run on a simulated clock; this moves both forward. */
async function advanceClocks(request: import("@playwright/test").APIRequestContext, days: number) {
  for (const port of [8084, 8085]) {
    const res = await request.post(`http://localhost:${port}/admin/clock/advance?days=${days}`);
    expect(res.ok()).toBeTruthy();
  }
}

/** The money arrives through Kafka a moment after the payment, so look again until it shows. */
async function eventually(page: Page, check: () => Promise<void>, timeout = 30_000) {
  await expect(async () => {
    await page.reload();
    // A reload renews the session; let that finish before the next reload can interrupt it.
    await page.waitForLoadState("networkidle");
    await check();
  }).toPass({ timeout, intervals: [1000, 2000, 3000] });
}


/** "€38.61" or "-€5.00" as minor units. */
const minor = (text: string | null) => Math.round(Number((text ?? "").replace(/[^0-9.-]/g, "")) * 100);

/**
 * What the platform holds on the Balances page, pending plus available. Other specs move the shared simulated
 * clock forward, which can make funds available at once, so the sum is what does not depend on the order of the specs.
 */
async function platformBalance(page: Page): Promise<number> {
  return minor(await page.getByTestId("balance-pending").textContent()) + minor(await page.getByTestId("balance-available").textContent());
}

test.describe("split payments", () => {
  test("a payment is shared with a connected account, refunded in proportion and paid out separately", async ({ page, context, request }) => {
    test.setTimeout(240_000);
    await signUp(page);

    // A restaurant on the platform, paid out to its own IBAN.
    await nav(page).getByRole("link", { name: "Connected accounts" }).click();
    await page.getByRole("button", { name: "New account" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name").fill("Pizza Palace");
    await dialog.getByLabel("IBAN").fill("BG80 BNBG 9661 1020 3456 78");
    await dialog.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByTestId("account-name")).toHaveText("Pizza Palace");
    const accountPath = new URL(page.url()).pathname;

    // A €100.00 order, €60.00 of it for the restaurant.
    await nav(page).getByRole("link", { name: "Payments" }).click();
    await page.getByRole("button", { name: "Create test payment" }).click();
    const create = page.getByRole("dialog");
    await create.getByLabel("Amount", { exact: true }).fill("100.00");
    await create.getByLabel("Send part of it to").selectOption({ label: "Pizza Palace" });
    await create.getByLabel("The account receives").fill("60.00");
    await create.getByRole("button", { name: "Create checkout" }).click();
    const [checkout] = await Promise.all([context.waitForEvent("page"), create.getByRole("link", { name: "Open checkout" }).click()]);
    await checkout.getByRole("form", { name: "Payment details" }).getByLabel("Email").fill("anna@example.com");
    await checkout.getByLabel("Card number").fill("4242 4242 4242 4242");
    await checkout.getByLabel("Expiry").fill("1299");
    await checkout.getByLabel("CVC").fill("123");
    await checkout.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible();
    await checkout.close();
    await create.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByTestId("payment-transfer")).toContainText("€60.00");

    // The restaurant is owed its €60.00; the platform keeps €40.00 minus the €1.39 card fee.
    await page.goto(accountPath);
    await page.waitForLoadState("networkidle");
    await eventually(page, async () => {
      await expect(page.getByTestId("account-activity").getByRole("row", { name: /Transfer.*€60\.00/ })).toBeVisible({ timeout: 1500 });
    });
    await nav(page).getByRole("link", { name: "Balances" }).click();
    await expect.poll(() => platformBalance(page), { timeout: 20_000 }).toBe(3861);

    // Refunding half of the order takes half of the restaurant share back (€30.00) and the platform covers the other €20.00.
    await nav(page).getByRole("link", { name: "Payments" }).click();
    await page.getByRole("link", { name: /100\.00/ }).first().click();
    await page.getByRole("button", { name: "Refund" }).click();
    const refund = page.getByRole("dialog");
    await refund.getByLabel(/Refund amount/).fill("50.00");
    await refund.getByRole("button", { name: "Refund" }).click();
    await expect(page.getByText("Partially refunded")).toBeVisible();
    await page.goto(accountPath);
    await page.waitForLoadState("networkidle");
    await eventually(page, async () => {
      await expect(page.getByTestId("account-activity").getByRole("row", { name: /Refund taken back.*-€30\.00/ })).toBeVisible({ timeout: 1500 });
    });
    await nav(page).getByRole("link", { name: "Balances" }).click();
    await expect.poll(() => platformBalance(page), { timeout: 20_000 }).toBe(1861);

    // After the settlement delay the restaurant's money is available and is paid out to its own IBAN.
    await advanceClocks(request, 3);
    await page.goto(accountPath);
    await page.waitForLoadState("networkidle");
    await eventually(
      page,
      async () => {
        await expect(page.getByTestId("account-pending")).toHaveText("€0.00", { timeout: 1500 });
        await expect(page.getByTestId("account-activity").getByText("Payout", { exact: true })).toBeVisible({ timeout: 1500 });
      },
      90_000,
    );
    await expect(page.getByTestId("account-available")).toHaveText("€0.00");
  });
});
