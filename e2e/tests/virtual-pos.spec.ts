import { expect, test, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });

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

/** The card inputs live in Cutpay's iframe, on Cutpay's origin. */
async function typeCardInFrame(page: Page, number: string) {
  const card = page.frameLocator('iframe[title="Secure card payment input"]');
  await card.getByLabel("Card number").fill(number);
  await card.getByLabel("Expiry date").fill("1299");
  await card.getByLabel("Security code").fill("123");
}

test.describe("virtual POS", () => {
  test("takes a phone order with a card that would need 3-D Secure online, without asking for it", async ({ page }) => {
    await signUp(page);
    await nav(page).getByRole("link", { name: "Terminal" }).click();
    await expect(page.getByRole("heading", { name: "Virtual POS" })).toBeVisible();

    await page.getByLabel("Amount").fill("42.00");
    await page.getByLabel("Description (optional)").fill("Phone order #12");
    await page.getByRole("button", { name: "Continue" }).click();

    await expect(page.getByTestId("charge-amount")).toHaveText("€42.00");
    // 4000 0000 0000 3220 sends an online customer to the bank's challenge page.
    await typeCardInFrame(page, "4000 0000 0000 3220");
    await page.getByRole("button", { name: "Charge €42.00" }).click();

    await expect(page.getByTestId("receipt-amount")).toHaveText("€42.00", { timeout: 30_000 });
    await expect(page.getByText("Card not present (phone or mail order)")).toBeVisible();
    await expect(page.getByText("Visa •••• 3220")).toBeVisible();
    await expect(page.getByText("Phone order #12")).toBeVisible();

    // The payment is recorded as a MOTO payment and was never put through 3-D Secure.
    await nav(page).getByRole("link", { name: "Payments" }).click();
    await page.getByRole("link", { name: /€42\.00|42\.00/ }).first().click();
    await expect(page.getByTestId("payment-channel")).toHaveText("Card not present (MOTO)");
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByText("Not required", { exact: true })).toBeVisible();
  });

  test("a declined card ends the charge and a new one can be started", async ({ page }) => {
    await signUp(page);
    await nav(page).getByRole("link", { name: "Terminal" }).click();
    await page.getByLabel("Amount").fill("10.00");
    await page.getByRole("button", { name: "Continue" }).click();

    await typeCardInFrame(page, "4000 0000 0000 0002");
    await page.getByRole("button", { name: "Charge €10.00" }).click();
    await expect(page.getByText("Your card was declined.")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: /^Charge/ })).toHaveCount(0);

    await page.getByRole("button", { name: "Start a new charge" }).click();
    await expect(page.getByRole("heading", { name: "Virtual POS" })).toBeVisible();
  });
});

test.describe("charge with QR", () => {
  test("shows a full-screen code and turns green within seconds of the customer paying", async ({ page, context }) => {
    await signUp(page);
    await nav(page).getByRole("link", { name: "Terminal" }).click();
    await page.getByRole("link", { name: "Charge with QR" }).click();
    // The form of the previous tab is replaced by this one; wait for the swap before typing.
    await expect(page.getByRole("heading", { name: "Charge with QR" })).toBeVisible();
    await page.getByLabel("Amount").fill("12.50");
    await page.getByLabel("What is it for? (optional)").fill("Table 4");
    await page.getByRole("button", { name: "Show QR code" }).click();

    const screen = page.getByRole("dialog", { name: "Charge with QR" });
    await expect(screen.getByTestId("qr-amount")).toHaveText("€12.50");
    await expect(screen.getByAltText("QR code to pay")).toBeVisible();
    await expect(screen.getByText("Waiting for payment…")).toBeVisible();

    // The customer scans the code; here the same address is opened in a second tab.
    const address = await screen.getByRole("link", { name: "Open payment page" }).getAttribute("href");
    const customer = await context.newPage();
    await customer.goto(address!);
    await customer.getByRole("form", { name: "Payment details" }).getByLabel("Email").fill("anna@example.com");
    await customer.getByLabel("Card number").fill("4242 4242 4242 4242");
    await customer.getByLabel("Expiry").fill("1299");
    await customer.getByLabel("CVC").fill("123");
    await customer.getByRole("button", { name: /^Pay/ }).click();
    const paidAt = Date.now();
    await expect(customer.getByRole("heading", { name: "Payment successful" })).toBeVisible();

    await expect(screen.getByText("Paid", { exact: true })).toBeVisible({ timeout: 5_000 });
    // Generous for a loaded CI machine; the screen normally turns green in about a second.
    expect(Date.now() - paidAt).toBeLessThan(5_000);
    await screen.getByRole("button", { name: "New charge" }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("closing the screen before payment cancels the charge so the code can no longer be used", async ({ page, context }) => {
    await signUp(page);
    await nav(page).getByRole("link", { name: "Terminal" }).click();
    await page.getByRole("link", { name: "Charge with QR" }).click();
    // The form of the previous tab is replaced by this one; wait for the swap before typing.
    await expect(page.getByRole("heading", { name: "Charge with QR" })).toBeVisible();
    await page.getByLabel("Amount").fill("3.00");
    await page.getByRole("button", { name: "Show QR code" }).click();
    const screen = page.getByRole("dialog", { name: "Charge with QR" });
    const address = await screen.getByRole("link", { name: "Open payment page" }).getAttribute("href");

    await screen.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);

    const customer = await context.newPage();
    await customer.goto(address!);
    await expect(customer.getByRole("heading", { name: "Payment canceled" })).toBeVisible();
  });
});
