import { expect, test, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const nav = (page: Page, name: string) =>
  page.getByRole("navigation", { name: "Main" }).getByRole("link", { name }).click();

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Rose Cafe");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe");
}

async function payHundredEuros(page: Page, context: import("@playwright/test").BrowserContext) {
  await nav(page, "Payments");
  await page.getByRole("button", { name: "Create test payment" }).click();
  await page.getByLabel("Amount").fill("100.00");
  await page.getByRole("button", { name: "Create checkout" }).click();
  const [checkout] = await Promise.all([
    context.waitForEvent("page"),
    page.getByRole("link", { name: "Open checkout" }).click(),
  ]);
  await checkout.getByLabel("Email").fill("anna@example.com");
  await checkout.getByLabel("Card number").fill("4242 4242 4242 4242");
  await checkout.getByLabel("Expiry").fill("1299");
  await checkout.getByLabel("CVC").fill("123");
  await checkout.getByRole("button", { name: /^Pay/ }).click();
  await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible();
}

/** The ledger and payouts services run on a simulated clock; this moves both forward. */
async function advanceClocks(request: import("@playwright/test").APIRequestContext, days: number) {
  for (const port of [8084, 8085]) {
    const res = await request.post(`http://localhost:${port}/admin/clock/advance?days=${days}`);
    expect(res.ok()).toBeTruthy();
  }
}

test.describe("balances and payouts", () => {
  test("a payment is pending, becomes available after the delay and is paid out", async ({ page, context, request }) => {
    await signUp(page);
    await payHundredEuros(page, context);

    await nav(page, "Balances");
    await expect(page.getByTestId("balance-pending")).toHaveText("€98.61");
    await expect(page.getByTestId("balance-available")).toHaveText("€0.00");

    await advanceClocks(request, 2);
    await page.reload();
    await expect(page.getByTestId("balance-available")).toHaveText("€98.61", { timeout: 15_000 });
    await expect(page.getByTestId("balance-pending")).toHaveText("€0.00");

    await page.goto("/settings/payouts");
    await page.getByLabel("IBAN").fill("BG80 BNBG 9661 1020 3456 78");
    await page.getByRole("button", { name: "Add bank account" }).click();
    await expect(page.getByText("•••• 5678")).toBeVisible();

    await nav(page, "Payouts");
    await page.getByRole("button", { name: "Pay out now" }).click();
    await page.getByRole("dialog").getByRole("button", { name: /Pay out/ }).click();
    await page.getByRole("link", { name: /€/ }).first().click();
    await expect(page.getByText("Paid", { exact: true })).toBeVisible({ timeout: 30_000 });
  });
});
