import { expect, test } from "@playwright/test";

const BACKOFFICE = "http://localhost:5177";

test.describe("back-office operations", () => {
  test("an admin runs the reconciliation and sees the dead letters", async ({ browser }) => {
    test.setTimeout(120_000);
    const context = await browser.newContext({ baseURL: BACKOFFICE });
    const page = await context.newPage();
    await page.goto("/");
    await page.getByLabel("Email").fill("admin@cutpay.test");
    await page.getByLabel("Password").fill("staff-password-123");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("staff-name")).toBeVisible();

    await page.getByRole("link", { name: "Operations" }).click();
    await expect(page.getByRole("heading", { name: "Operations", level: 1 })).toBeVisible();

    // The three accounts of the money are compared on demand; the result is a new row, clean or with named differences.
    const started = page.waitForResponse((r) => r.url().endsWith("/v1/staff/reconciliation/runs") && r.request().method() === "POST", { timeout: 60_000 });
    await page.getByRole("button", { name: "Run now" }).click();
    expect((await started).status()).toBe(200);
    await expect(page.getByTestId("recon-run").first()).toContainText(/clean|differences/);

    // Dead letters are listed (possibly none) and an admin is offered the actions on any that are there.
    await expect(page.getByRole("heading", { name: "Dead letters" })).toBeVisible();
    await expect(page.getByLabel("Dead letters")).toBeVisible();
    await context.close();
  });

  test("a reviewer can look but is not offered the actions", async ({ browser }) => {
    const context = await browser.newContext({ baseURL: BACKOFFICE });
    const page = await context.newPage();
    await page.goto("/");
    await page.getByLabel("Email").fill("reviewer@cutpay.test");
    await page.getByLabel("Password").fill("staff-password-123");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("link", { name: "Operations" }).click();
    await expect(page.getByText("Only admins can act on them.")).toBeVisible();
    await context.close();
  });
});
