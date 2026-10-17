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

/** Creates a fixed-price link in the dashboard and returns the address customers open. */
async function createLink(page: Page, options: { title: string; amount: string; maxUses?: string; message?: string }): Promise<string> {
  await nav(page).getByRole("link", { name: "Links" }).click();
  await page.getByRole("link", { name: "Create link" }).click();
  await page.getByLabel("Name", { exact: true }).fill(options.title);
  await page.getByLabel("Amount", { exact: true }).fill(options.amount);
  if (options.maxUses) await page.getByLabel("Limit number of payments (optional)").fill(options.maxUses);
  if (options.message) await page.getByLabel("Message (optional)").fill(options.message);
  await expect(page.getByTestId("preview-amount")).toHaveText(`€${Number(options.amount).toFixed(2)}`);
  await page.getByRole("button", { name: "Create link" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(options.title) })).toBeVisible();
  const url = (await page.getByTestId("link-url").textContent()) ?? "";
  expect(url).toMatch(/\/l\/[1-9A-HJ-NP-Za-km-z]{10}$/);
  return url;
}

async function payWithCard(page: Page, number: string) {
  await page.getByLabel("Email").fill("anna@example.com");
  await page.getByLabel("Card number").fill(number);
  await page.getByLabel("Expiry").fill("1299");
  await page.getByLabel("CVC").fill("123");
  await page.getByRole("button", { name: /^Pay/ }).click();
}

test.describe("payment links", () => {
  test("a customer pays through a link, sees the merchant's message, and the link closes after its last use", async ({ page, context }) => {
    await signUp(page);
    const url = await createLink(page, { title: "Table 4", amount: "15.00", maxUses: "1", message: "Thank you! See you at table 4." });

    // The customer opens the link in their own tab.
    const customer = await context.newPage();
    await customer.goto(url);
    await expect(customer.getByRole("heading", { name: "Table 4" })).toBeVisible();
    await expect(customer.getByTestId("amount")).toHaveText("€15.00");
    await customer.getByRole("button", { name: "Continue to payment" }).click();

    await expect(customer.getByRole("heading", { name: "Pay by card" })).toBeVisible();
    await expect(customer.getByTestId("amount")).toHaveText("€15.00");
    await payWithCard(customer, "4242 4242 4242 4242");
    await expect(customer.getByRole("heading", { name: "Payment successful" })).toBeVisible();
    await customer.getByRole("link", { name: /Return to/ }).click();
    await expect(customer.getByText("Thank you! See you at table 4.")).toBeVisible();

    // Back in the dashboard the payment is counted a moment later (it arrives through Kafka) and the link has closed.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText("Inactive", { exact: true }).first()).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 30_000 });
    await expect(page.getByText("1 of 1")).toBeVisible();
    await expect(page.getByRole("link", { name: /^pay_/ })).toHaveCount(1);

    // Nobody else can pay it.
    const latecomer = await context.newPage();
    await latecomer.goto(url);
    await expect(latecomer.getByText("This payment link is no longer active.")).toBeVisible();
    await expect(latecomer.getByRole("button", { name: "Continue to payment" })).toHaveCount(0);
  });

  test("when two customers go for the last use at the same moment, exactly one gets it", async ({ page, context }) => {
    await signUp(page);
    const url = await createLink(page, { title: "Last ticket", amount: "30.00", maxUses: "1" });

    const first = await context.newPage();
    const second = await context.newPage();
    await Promise.all([first.goto(url), second.goto(url)]);
    // Both pages show the link as open, because nobody has started paying yet.
    await expect(first.getByRole("button", { name: "Continue to payment" })).toBeVisible();
    await expect(second.getByRole("button", { name: "Continue to payment" })).toBeVisible();

    await Promise.all([
      first.getByRole("button", { name: "Continue to payment" }).click(),
      second.getByRole("button", { name: "Continue to payment" }).click(),
    ]);

    const reachedPayment = async (p: Page) =>
      p
        .getByRole("heading", { name: "Pay by card" })
        .isVisible({ timeout: 10_000 })
        .catch(() => false);
    await expect(async () => {
      const [a, b] = await Promise.all([reachedPayment(first), reachedPayment(second)]);
      expect([a, b].filter(Boolean)).toHaveLength(1);
    }).toPass({ timeout: 20_000 });

    const loser = (await reachedPayment(first)) ? second : first;
    await expect(loser.getByText("This payment link is no longer available.")).toBeVisible();
    await expect(loser.getByRole("heading", { name: "Pay by card" })).toHaveCount(0);
  });

  test("a link has a QR code, a printable table card and ready-made sharing buttons", async ({ page }) => {
    await signUp(page);
    const url = await createLink(page, { title: "Counter", amount: "5.00" });

    await expect(page.getByAltText("QR code for Counter")).toBeVisible();
    const sharing = page.getByRole("link", { name: "WhatsApp" });
    await expect(sharing).toHaveAttribute("href", new RegExp(`^https://wa\\.me/\\?text=.*${encodeURIComponent(url).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`));
    await expect(page.getByRole("link", { name: "Email" })).toHaveAttribute("href", /^mailto:/);

    const [pdf] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Table card (PDF)" }).click()]);
    expect(pdf.suggestedFilename()).toMatch(/-table-card\.pdf$/);
    const [png] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "PNG" }).click()]);
    expect(png.suggestedFilename()).toMatch(/\.png$/);
  });

  test("a customer-chooses link takes the amount the customer types, within the range", async ({ page, context }) => {
    await signUp(page);
    await nav(page).getByRole("link", { name: "Links" }).click();
    await page.getByRole("link", { name: "Create link" }).click();
    await page.getByLabel("Name", { exact: true }).fill("Tip jar");
    await page.getByLabel("Customer chooses").check();
    await page.getByLabel("Minimum (optional)").fill("2.00");
    await page.getByLabel("Maximum (optional)").fill("50.00");
    await page.getByRole("button", { name: "Create link" }).click();
    await expect(page.getByRole("heading", { name: /Tip jar/ })).toBeVisible();
    const url = (await page.getByTestId("link-url").textContent()) ?? "";

    const customer = await context.newPage();
    await customer.goto(url);
    await customer.getByLabel("Amount (EUR)").fill("1");
    await customer.getByRole("button", { name: "Continue to payment" }).click();
    await expect(customer.getByText("The minimum is €2.00")).toBeVisible();
    await customer.getByLabel("Amount (EUR)").fill("7.50");
    await customer.getByRole("button", { name: "Continue to payment" }).click();
    await expect(customer.getByTestId("amount")).toHaveText("€7.50");
  });
});
