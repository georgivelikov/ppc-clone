import { expect, test, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Grand Hotel");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Grand Hotel");
}

/** Holds an amount on a card at the virtual terminal, the way a hotel does at check-in. */
async function holdAtTerminal(page: Page, amount: string) {
  await nav(page).getByRole("link", { name: "Terminal" }).click();
  await page.getByLabel("Amount").fill(amount);
  await page.getByLabel("Description (optional)").fill("Room 12, three nights");
  await page.getByLabel("Pre-authorise only (capture later)").check();
  await page.getByRole("button", { name: "Continue" }).click();

  const card = page.frameLocator('iframe[title="Secure card payment input"]');
  await card.getByLabel("Card number").fill("4242 4242 4242 4242");
  await card.getByLabel("Expiry date").fill("1299");
  await card.getByLabel("Security code").fill("123");
  await page.getByRole("button", { name: `Charge €${amount}` }).click();

  // A held payment is not a sale yet, so the terminal goes to the payment instead of a receipt.
  await expect(page.getByTestId("payment-authorized")).toHaveText(`€${amount}`, { timeout: 30_000 });
  await expect(page.getByText("Uncaptured", { exact: true })).toBeVisible();
}

test.describe("pre-authorisation", () => {
  test("holds €200 at check-in, captures €150 at check-out and only that is booked", async ({ page }) => {
    test.setTimeout(90_000);
    await signUp(page);
    await holdAtTerminal(page, "200.00");

    await page.getByRole("button", { name: "Capture", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Amount to capture/).fill("150.00");
    await expect(dialog.getByTestId("capture-release-note")).toContainText("€50.00 will be released");
    await dialog.getByRole("button", { name: "Capture", exact: true }).click();

    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByTestId("payment-amount")).toHaveText("€150.00");
    await expect(page.getByTestId("payment-authorized")).toHaveText("€200.00");
    await expect(page.getByText("Captured €150.00 of €200.00 held; the rest was released")).toBeVisible();

    // The ledger priced the captured €150, not the €200 that was held.
    await expect(page.getByTestId("payment-fee")).toHaveText(/^−€2\.0[34]$/, { timeout: 20_000 });
    await expect(page.getByTestId("payment-net")).toHaveText(/^€147\.9[67]$/);

    // Only what was captured can be refunded.
    await page.getByRole("button", { name: "Refund" }).click();
    await expect(page.getByRole("dialog").getByText(/€150\.00 of €150\.00 can still be refunded/)).toBeVisible();
  });

  test("releasing a hold gives the money back without a sale", async ({ page }) => {
    await signUp(page);
    await holdAtTerminal(page, "80.00");

    await page.getByRole("button", { name: "Release hold" }).click();
    await expect(page.getByText("Canceled", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Capture", exact: true })).toHaveCount(0);
    await expect(page.getByTestId("payment-fee")).toHaveCount(0);
  });

  test("an uncaptured hold is released on its own after seven days", async ({ page, request }) => {
    test.setTimeout(90_000);
    await signUp(page);
    await holdAtTerminal(page, "60.00");
    await expect(page.getByTestId("payment-capture-before")).toBeVisible();

    const advanced = await request.post("http://localhost:8082/admin/clock/advance?days=8");
    expect(advanced.ok()).toBeTruthy();

    // The payments service looks for expired holds every few seconds.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText("Hold released: not captured in time")).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 30_000 });
    await expect(page.getByText("Canceled", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Capture", exact: true })).toHaveCount(0);
  });
});
