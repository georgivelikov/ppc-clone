import { expect, type BrowserContext, type Page } from "@playwright/test";

export const PASSWORD = "correct-horse-battery";
export const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
export const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });

export async function signUp(page: Page, country = "BG") {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Sofa Studio");
  await page.getByLabel("Country").selectOption(country);
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Sofa Studio");
}

/** Creates a test payment in the dashboard and returns the hosted checkout opened in a new tab. */
export async function startCheckout(page: Page, context: BrowserContext, amount: string, currency?: string): Promise<Page> {
  // The dialog of an earlier checkout would cover the navigation.
  const open = page.getByRole("dialog");
  if (await open.count()) await open.getByRole("button", { name: "Close" }).click();
  await nav(page).getByRole("link", { name: "Payments" }).click();
  await page.getByRole("button", { name: "Create test payment" }).click();
  await page.getByLabel("Amount").fill(amount);
  if (currency) await page.getByLabel("Currency").selectOption(currency);
  await page.getByRole("button", { name: "Create checkout" }).click();
  await expect(page.getByRole("dialog", { name: "Checkout is ready" })).toBeVisible();

  const [checkout] = await Promise.all([
    context.waitForEvent("page"),
    page.getByRole("link", { name: "Open checkout" }).click(),
  ]);
  await expect(checkout.getByRole("tab", { name: "Card" })).toBeVisible({ timeout: 20_000 });
  return checkout;
}
