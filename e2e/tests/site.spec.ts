import { expect, test } from "@playwright/test";

const SITE = "http://localhost:5176";

test.describe("site", () => {
  test("the marketing pages load and the fee calculator matches the platform", async ({ page }) => {
    await page.goto(SITE);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Get paid by card");

    await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Pricing" }).click();
    await expect(page.getByTestId("calc-fee")).toHaveText("−€1.39");
    await expect(page.getByTestId("calc-net")).toHaveText("€98.61");
  });

  test("sign-up leads to the dashboard", async ({ page }) => {
    await page.goto(SITE);
    await page.getByRole("link", { name: "Start for free" }).click();
    await expect(page).toHaveURL(/localhost:5173\/signup/);
    await expect(page.getByRole("button", { name: "Create account" })).toBeVisible();
  });

  test("the API reference renders every area of the OpenAPI description", async ({ page }) => {
    await page.goto(`${SITE}/docs/api`);
    // Redoc builds its own navigation from the description's tags and sections.
    const sidebar = page.getByRole("list").filter({ has: page.getByRole("link", { name: "Idempotency" }) });
    await expect(sidebar.getByRole("link", { name: "Idempotency" })).toBeVisible({ timeout: 30_000 });
    for (const tag of ["Payments", "Refunds", "Balance", "Payouts", "Webhooks", "Events"]) {
      await expect(sidebar.getByRole("link", { name: tag, exact: true }).last()).toBeVisible();
    }

    // Opening an area lists its operations, taken from the OpenAPI file.
    await sidebar.getByRole("link", { name: "Payments", exact: true }).last().click();
    await expect(page.getByText("Create a payment").first()).toBeVisible();
    await expect(page.getByText("/v1/payment_intents").first()).toBeVisible();

    // The description survives a reload at a deep address (the site router hands it to Redoc).
    await page.reload();
    await expect(page.getByText("Create a payment").first()).toBeVisible({ timeout: 30_000 });
  });

  test("the docs pages are reachable from the navigation", async ({ page }) => {
    await page.goto(`${SITE}/docs`);
    await page.getByRole("navigation", { name: "Docs" }).getByRole("link", { name: "Test cards" }).click();
    await expect(page.getByRole("heading", { name: "Test cards" })).toBeVisible();
    await page.getByRole("navigation", { name: "Docs" }).getByRole("link", { name: "Webhooks" }).click();
    await expect(page.getByRole("heading", { name: "Check the signature" })).toBeVisible();
  });
});
