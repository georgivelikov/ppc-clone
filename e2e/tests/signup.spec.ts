import { expect, test, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";

// A fresh address per run, so the test can be repeated against the same database.
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

async function signUp(page: Page, email: string) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Rose Cafe");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
}

test.describe("merchant authentication", () => {
  test("signs up, survives a reload, signs out and signs back in", async ({ page }) => {
    const email = uniqueEmail();

    await signUp(page, email);
    await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe");
    await expect(page.getByRole("heading", { name: "Welcome, Ivan" })).toBeVisible();
    await expect(page.getByText(/viewing test data/)).toBeVisible();

    // The access token lives only in memory, so a reload proves the HttpOnly refresh cookie works.
    await page.reload();
    await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe");

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

    // After sign-out the session is really gone, not just hidden.
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe");
  });

  test("rejects a wrong password and a duplicate email", async ({ page }) => {
    const email = uniqueEmail();
    await signUp(page, email);
    await expect(page.getByTestId("merchant-name")).toBeVisible();
    await page.getByRole("button", { name: "Sign out" }).click();

    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill("not-the-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert")).toHaveText("Incorrect email or password");

    await signUp(page, email);
    await expect(page.getByText("An account with this email already exists")).toBeVisible();
  });

  test("edits the business profile", async ({ page }) => {
    await signUp(page, uniqueEmail());
    await expect(page.getByTestId("merchant-name")).toBeVisible();

    await page.getByRole("link", { name: "Settings" }).click();
    await page.getByLabel("Business name").fill("Rose Cafe Sofia");
    await page.getByLabel("Settlement currency").selectOption("RON");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByText("Changes saved.")).toBeVisible();
    await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe Sofia");

    // Persisted on the server, not just in React state.
    await page.reload();
    await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe Sofia");
  });

  test("keeps the sidebar entries for later phases disabled", async ({ page }) => {
    await signUp(page, uniqueEmail());
    const nav = page.getByRole("navigation", { name: "Main" });
    await expect(nav).toBeVisible();
    // Built so far: payments, balances, payouts and the developer tools.
    for (const built of ["Payments", "Balances", "Payouts", "Developers", "Settings"]) {
      await expect(nav.getByRole("link", { name: built })).toBeVisible();
    }
    // Still to come.
    for (const later of ["Links", "Customers", "Subscriptions"]) {
      await expect(nav.getByRole("link", { name: later })).toHaveCount(0);
      await expect(nav.getByText(later)).toHaveAttribute("aria-disabled", "true");
    }
  });
});
