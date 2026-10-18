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

async function createCustomer(page: Page) {
  await nav(page).getByRole("link", { name: "Customers" }).click();
  await page.getByRole("button", { name: "New customer" }).click();
  await page.getByLabel("Name").fill("Anna Petrova");
  await page.getByLabel("Email").fill("anna@example.com");
  await page.getByRole("button", { name: "Create customer" }).click();
  await expect(page.getByTestId("customer-name")).toHaveText("Anna Petrova");
}

/** The merchant sends a link; the customer opens it on their own device and saves a card. */
async function saveCardAsCustomer(page: Page, context: BrowserContext, number: string, authenticate = false) {
  await page.getByRole("button", { name: "Add a card" }).click();
  await page.getByRole("button", { name: "Create card page" }).click();
  const url = await page.getByTestId("setup-url").inputValue();
  expect(url).toMatch(/\/s\/seti_/);

  const customer = await context.newPage();
  await customer.goto(url);
  await expect(customer.getByRole("heading", { name: "Save your card" })).toBeVisible();
  await expect(customer.getByText(/You will not be charged now/)).toBeVisible();
  await customer.getByLabel("Card number").fill(number);
  await customer.getByLabel("Expiry").fill("1299");
  await customer.getByLabel("CVC").fill("123");
  await customer.getByRole("button", { name: "Save card" }).click();
  if (authenticate) {
    await expect(customer.getByRole("heading", { name: "Confirm with your bank" })).toBeVisible();
    await customer.getByLabel("One-time code").fill("1234");
    await customer.getByRole("button", { name: "Approve" }).click();
  }
  await expect(customer.getByRole("heading", { name: "Card saved" })).toBeVisible();
  await customer.close();

  // The merchant's window notices on its own.
  await expect(page.getByText("Card saved.")).toBeVisible({ timeout: 15_000 });
  await page.getByRole("dialog").getByRole("button", { name: "Done" }).click();
}

async function chargeSavedCard(page: Page, amount: string) {
  await page.getByRole("button", { name: "Charge saved card" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel(/Amount/).fill(amount);
  await dialog.getByRole("button", { name: "Charge", exact: true }).click();
  return dialog;
}

test.describe("saved cards", () => {
  test("a customer saves a card on a hosted page and the merchant charges it without them", async ({ page, context }) => {
    test.setTimeout(90_000);
    await signUp(page);
    await createCustomer(page);
    await saveCardAsCustomer(page, context, "4242 4242 4242 4242");
    await expect(page.getByTestId("saved-card")).toHaveText("Visa •••• 4242");
    await expect(page.getByText("Default")).toBeVisible();

    const dialog = await chargeSavedCard(page, "19.00");
    await expect(dialog.getByText("Charged €19.00.")).toBeVisible({ timeout: 20_000 });
    await dialog.getByRole("button", { name: "Close" }).first().click();

    await expect(async () => {
      await page.reload();
      await expect(page.getByRole("link", { name: "€19.00" })).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 15_000 });
    await page.getByRole("link", { name: "€19.00" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByTestId("card-summary")).toHaveText("visa •••• 4242");
  });

  test("a card that asks for 3-D Secure is authenticated once and then charged without the customer", async ({ page, context }) => {
    test.setTimeout(90_000);
    await signUp(page);
    await createCustomer(page);
    await saveCardAsCustomer(page, context, "4000 0000 0000 3220", true);

    const dialog = await chargeSavedCard(page, "9.00");
    await expect(dialog.getByText("Charged €9.00.")).toBeVisible({ timeout: 20_000 });
  });

  test("a card that needs the customer every time cannot be charged without them", async ({ page, context }) => {
    test.setTimeout(90_000);
    await signUp(page);
    await createCustomer(page);
    await saveCardAsCustomer(page, context, "4000 0027 6000 3184", true);

    const dialog = await chargeSavedCard(page, "9.00");
    await expect(dialog.getByText(/needs the customer to authenticate/)).toBeVisible({ timeout: 20_000 });
  });
});
