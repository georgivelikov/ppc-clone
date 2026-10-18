import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Coffee Club");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Coffee Club");
}

/** Creates a product with one monthly price. */
async function createProduct(page: Page, name: string, price: string, trialDays = "0") {
  await nav(page).getByRole("link", { name: "Products" }).click();
  await page.getByRole("button", { name: "New product" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name", { exact: true }).fill(name);
  await dialog.getByLabel("Price", { exact: true }).fill(price);
  await dialog.getByLabel("Free trial (days)").fill(trialDays);
  await dialog.getByRole("button", { name: "Create product" }).click();
  await expect(page.getByRole("heading", { name, exact: true, level: 2 })).toBeVisible();
}

/** A customer with a saved card, as if they had filled in the hosted card page. */
async function createCustomerWithCard(page: Page, context: BrowserContext, cardNumber: string) {
  await nav(page).getByRole("link", { name: "Customers" }).click();
  await page.getByRole("button", { name: "New customer" }).click();
  await page.getByLabel("Name").fill("Anna Petrova");
  await page.getByLabel("Email").fill("anna@example.com");
  await page.getByRole("button", { name: "Create customer" }).click();
  await expect(page.getByTestId("customer-name")).toHaveText("Anna Petrova");

  await page.getByRole("button", { name: "Add a card" }).click();
  await page.getByRole("button", { name: "Create card page" }).click();
  const url = await page.getByTestId("setup-url").inputValue();
  const customer = await context.newPage();
  await customer.goto(url);
  await customer.getByLabel("Card number").fill(cardNumber);
  await customer.getByLabel("Expiry").fill("1299");
  await customer.getByLabel("CVC").fill("123");
  await customer.getByRole("button", { name: "Save card" }).click();
  await expect(customer.getByRole("heading", { name: "Card saved" })).toBeVisible();
  await customer.close();
  await expect(page.getByText("Card saved.")).toBeVisible({ timeout: 15_000 });
  await page.getByRole("dialog").getByRole("button", { name: "Done" }).click();
}

async function startSubscription(page: Page, plan: RegExp) {
  await nav(page).getByRole("link", { name: "Subscriptions" }).click();
  await page.getByRole("link", { name: "New subscription" }).click();
  await page.getByPlaceholder("Search customers").fill("Anna");
  await page.getByRole("button", { name: /Anna Petrova/ }).click();
  await page.getByLabel("Plan").selectOption({ label: await page.getByLabel("Plan").locator("option", { hasText: plan }).first().innerText() });
  await page.getByRole("button", { name: "Start subscription" }).click();
  await expect(page.getByTestId("subscription-status")).toBeVisible();
}

/** The billing service runs on a simulated clock; this moves it forward. */
async function advanceBilling(request: import("@playwright/test").APIRequestContext, days: number) {
  const res = await request.post(`http://localhost:8088/admin/clock/advance?days=${days}`);
  expect(res.ok()).toBeTruthy();
}

/** The billing run looks for work every few seconds, so look again until the page shows the result. */
async function eventually(page: Page, check: () => Promise<void>, timeout = 45_000) {
  await expect(async () => {
    await page.reload();
    await check();
  }).toPass({ timeout, intervals: [1000, 2000, 3000] });
}

test.describe("subscriptions", () => {
  test("a free trial turns into a paid subscription that renews every month", async ({ page, context, request }) => {
    test.setTimeout(180_000);
    await signUp(page);
    await createProduct(page, "Coffee club", "15.00", "7");
    await createCustomerWithCard(page, context, "4242 4242 4242 4242");
    await startSubscription(page, /Coffee club/);

    await expect(page.getByTestId("subscription-status")).toHaveText("Trial");
    await expect(page.getByTestId("trial-end")).toBeVisible();
    await expect(page.getByTestId("subscription-invoices").getByRole("row")).toHaveCount(0);
    await expect(page.getByTestId("upcoming-total")).toHaveText("€15.00");

    // After the 7 days of the trial the first invoice is created and paid.
    await advanceBilling(request, 8);
    await eventually(page, async () => {
      await expect(page.getByTestId("subscription-status")).toHaveText("Active", { timeout: 1500 });
    });
    await expect(page.getByTestId("subscription-invoices").getByRole("row")).toHaveCount(1);
    await expect(page.getByTestId("subscription-invoices").getByText("Paid")).toBeVisible();

    // A month later the next period is billed.
    await advanceBilling(request, 32);
    await eventually(page, async () => {
      await expect(page.getByTestId("subscription-invoices").getByRole("row")).toHaveCount(2, { timeout: 1500 });
    });
    await expect(page.getByTestId("subscription-status")).toHaveText("Active");

    // The figures count it as recurring revenue.
    await nav(page).getByRole("link", { name: "Subscriptions" }).click();
    await expect(page.getByTestId("mrr")).toHaveText("€15.00");
    await expect(page.getByTestId("count-active")).toHaveText("1");

    // The customer was emailed a receipt for each invoice.
    await nav(page).getByRole("link", { name: "Invoices" }).click();
    await page.getByRole("link", { name: "INV-0001" }).click();
    await expect(page.getByTestId("invoice-status")).toHaveText("Paid");
    await expect(page.getByText("Receipt for invoice INV-0001")).toBeVisible();
  });

  test("a card that stops working is retried and then the subscription is canceled, with emails at every step", async ({ page, context, request }) => {
    test.setTimeout(240_000);
    await signUp(page);
    await createProduct(page, "Coffee club", "15.00", "7");
    // This card can be saved, but every charge is declined.
    await createCustomerWithCard(page, context, "4000 0000 0000 0341");
    await startSubscription(page, /Coffee club/);
    await expect(page.getByTestId("subscription-status")).toHaveText("Trial");

    // The trial ends and the first charge fails.
    await advanceBilling(request, 8);
    await eventually(page, async () => {
      await expect(page.getByTestId("subscription-status")).toHaveText("Past due", { timeout: 1500 });
    });
    await expect(page.getByTestId("emails").getByText(/Payment for invoice INV-0001 failed/)).toHaveCount(1);

    // The retries are due on days 1, 3, 5 and 7 after that. Moving a week on lets them all run, one every few seconds.
    await advanceBilling(request, 8);
    await eventually(
      page,
      async () => {
        await expect(page.getByTestId("subscription-status")).toHaveText("Canceled", { timeout: 1500 });
      },
      90_000,
    );
    await expect(page.getByText(/Payment for invoice INV-0001 failed/)).toHaveCount(4);
    await expect(page.getByText("Your subscription was canceled").first()).toBeVisible();
    await expect(page.getByTestId("subscription-invoices").getByText("Uncollectible")).toBeVisible();

    await page.getByTestId("subscription-invoices").getByRole("link", { name: "INV-0001" }).click();
    await expect(page.getByTestId("invoice-status")).toHaveText("Uncollectible");
    await expect(page.getByTestId("attempts").getByRole("listitem")).toHaveCount(4);
  });

  test("upgrading in the middle of the month credits the old plan and charges the rest at the new one", async ({ page, context, request }) => {
    test.setTimeout(180_000);
    await signUp(page);
    await createProduct(page, "Basic", "10.00");
    await createProduct(page, "Pro", "30.00");
    await createCustomerWithCard(page, context, "4242 4242 4242 4242");
    await startSubscription(page, /Basic/);
    await expect(page.getByTestId("subscription-status")).toHaveText("Active");

    // Half way through the month.
    await advanceBilling(request, 15);
    await page.reload();
    await page.getByRole("button", { name: "Change plan" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Plan", { exact: true }).selectOption({ label: await dialog.getByLabel("Plan", { exact: true }).locator("option", { hasText: /Pro/ }).first().innerText() });

    const preview = dialog.getByTestId("proration-preview");
    await expect(preview.getByText(/Unused time on Basic/)).toBeVisible();
    await expect(preview.getByText(/Remaining time on Pro/)).toBeVisible();
    // Roughly half of the €20 difference.
    await expect(dialog.getByTestId("proration-net")).toHaveText(/^€(9|10|11)\.\d\d$/);
    await dialog.getByRole("button", { name: "Change plan" }).click();
    await expect(dialog).toHaveCount(0);

    // The proration waits for the next invoice, which now shows the new plan and both lines.
    await expect(page.getByTestId("subscription-total")).toContainText("€30.00");
    await expect(page.getByLabel("Upcoming invoice").getByText(/Unused time on Basic/)).toBeVisible();
    await expect(page.getByLabel("Upcoming invoice").getByText(/Remaining time on Pro/)).toBeVisible();
  });
});

test.describe("invoices", () => {
  test("a merchant sends a one-off invoice and the customer pays it on the hosted page", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page);
    await createCustomerWithCard(page, context, "4242 4242 4242 4242");

    await nav(page).getByRole("link", { name: "Invoices" }).click();
    await page.getByRole("link", { name: "Create invoice" }).click();
    await page.getByPlaceholder("Search customers").fill("Anna");
    await page.getByRole("button", { name: /Anna Petrova/ }).click();
    await page.getByLabel("Description 1").fill("Catering");
    await page.getByLabel("Unit price 1").fill("100.00");
    await page.getByLabel("Quantity 1").fill("2");
    await page.getByRole("button", { name: "Add line" }).click();
    await page.getByLabel("Description 2").fill("Loyalty discount");
    await page.getByLabel("Unit price 2").fill("-20.00");
    await page.getByLabel("Tax % (optional)").fill("20");
    await expect(page.getByTestId("draft-subtotal")).toHaveText("€180.00");
    await expect(page.getByTestId("draft-total")).toHaveText("€216.00");
    await page.getByRole("button", { name: "Create and send" }).click();

    await expect(page.getByTestId("invoice-number")).toHaveText("INV-0001");
    await expect(page.getByTestId("invoice-status")).toHaveText("Open");
    await expect(page.getByTestId("invoice-total")).toHaveText("€216.00");

    // The customer opens the link and pays with a card on the hosted checkout.
    const hosted = await context.newPage();
    const link = await page.getByRole("link", { name: "Customer page" }).getAttribute("href");
    await hosted.goto(link!);
    await expect(hosted.getByTestId("invoice-total")).toHaveText("€216.00");
    await hosted.getByRole("button", { name: /^Pay €216\.00/ }).click();
    await hosted.getByLabel("Card number").fill("4242 4242 4242 4242");
    await hosted.getByLabel("Expiry").fill("1299");
    await hosted.getByLabel("CVC").fill("123");
    await hosted.getByLabel("Email").fill("anna@example.com");
    await hosted.getByRole("button", { name: /^Pay/ }).click();
    await expect(hosted.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });
    await hosted.getByRole("link", { name: /Return to/ }).click();
    await expect(hosted.getByTestId("invoice-status")).toHaveText("Paid", { timeout: 30_000 });
    await hosted.close();

    await eventually(page, async () => {
      await expect(page.getByTestId("invoice-status")).toHaveText("Paid", { timeout: 1500 });
    });
    await expect(page.getByTestId("invoice-paid")).toHaveText("€216.00");
  });
});
