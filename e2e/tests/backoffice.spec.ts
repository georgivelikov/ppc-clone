import { expect, test, type Browser, type Page } from "@playwright/test";
import { fillAndSubmit, inbox, nav, signUp } from "./support";

const BACKOFFICE = "http://localhost:5177";

/** Staff work in a browser of their own: a different site, a different sign-in. */
async function staff(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: BACKOFFICE });
  const page = await context.newPage();
  await page.goto("/");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("staff-password-123");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByTestId("staff-name")).toBeVisible();
  return page;
}

test.describe("back-office", () => {
  test("a risky application waits for a person, who approves it; payouts are held; an adjustment needs a second admin", async ({
    page,
    browser,
    request,
  }) => {
    test.setTimeout(300_000);
    const email = await signUp(page);
    // Gambling is a restricted industry, so the checks alone are not enough.
    await fillAndSubmit(page, "AB123451", { industry: "gambling" });
    await expect(page.getByTestId("application-state")).toHaveText("In review");
    await expect(page.getByText("A person at Cutpay will read this application")).toBeVisible();

    // A wrong password gets nowhere, and a business account is no staff account.
    const reviewerPage = await browser.newContext({ baseURL: BACKOFFICE }).then((c) => c.newPage());
    await reviewerPage.goto("/");
    await reviewerPage.getByLabel("Email").fill(email);
    await reviewerPage.getByLabel("Password").fill("correct-horse-battery");
    await reviewerPage.getByRole("button", { name: "Sign in" }).click();
    await expect(reviewerPage.getByRole("alert")).toContainText("Incorrect email or password");
    await reviewerPage.context().close();

    // The reviewer finds it in the queue and reads it.
    const reviewer = await staff(browser, "reviewer@cutpay.test");
    await reviewer.getByRole("link", { name: "Review queue" }).click();
    const row = reviewer.getByTestId("queue-row").filter({ hasText: "needs a person" }).last(); // oldest first: ours is the newest
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.click();
    await expect(reviewer.getByTestId("risk-score")).toHaveText("60");
    await expect(reviewer.getByText("Restricted industry: gambling")).toBeVisible();
    await expect(reviewer.getByTestId("person-Maria Ivanova")).toContainText(/check: (pending|approved)/);
    const merchantId = reviewer.url().split("/").pop()!;

    // Rejecting needs a reason; approving does not.
    await reviewer.getByRole("button", { name: "Reject" }).click();
    await expect(reviewer.getByRole("alert")).toContainText("Say why");
    await reviewer.getByRole("button", { name: "Approve" }).click();
    await expect(reviewer.getByTestId("application-status")).toHaveText("active");
    await expect(reviewer.getByTestId("decision")).toContainText("reviewer@cutpay.test");

    // The merchant hears about it, in the dashboard and by email.
    await expect(page.getByTestId("application-state")).toHaveText("Approved", { timeout: 30_000 });
    await inbox(request, email, /is approved/);
    await nav(page).getByRole("link", { name: "Settings" }).click();
    await page.getByRole("link", { name: "Activity", exact: true }).click();
    await expect(page.getByTestId("audit-event").filter({ hasText: "staff.application_approve" })).toBeVisible();

    // A reviewer cannot hold payouts or move money.
    await reviewer.getByRole("link", { name: "Businesses" }).click();
    await reviewer.getByLabel("Name, id or owner email").fill(merchantId);
    await reviewer.getByRole("button", { name: "Search" }).click();
    await reviewer.getByTestId("merchant-row").click();
    await expect(reviewer.getByText("Only admins can hold them")).toBeVisible();
    await reviewer.getByRole("link", { name: "Adjustments" }).click();
    await expect(reviewer.getByText("Only admins can ask for or approve adjustments")).toBeVisible();
    // But a reviewer can mark a business.
    await reviewer.getByRole("link", { name: "Businesses" }).click();
    await reviewer.getByLabel("Name, id or owner email").fill(merchantId);
    await reviewer.getByRole("button", { name: "Search" }).click();
    await reviewer.getByTestId("merchant-row").click();
    await reviewer.getByLabel("Note").fill("Gambling: check the licence");
    await reviewer.getByRole("button", { name: "Add mark" }).click();
    await expect(reviewer.getByTestId("flag-watchlist")).toContainText("check the licence");
    await reviewer.context().close();

    // An admin holds the payouts.
    const admin = await staff(browser, "admin@cutpay.test");
    await admin.getByRole("link", { name: "Businesses" }).click();
    await admin.getByLabel("Name, id or owner email").fill(merchantId);
    await admin.getByRole("button", { name: "Search" }).click();
    await admin.getByTestId("merchant-row").click();
    await admin.getByLabel("Why are payouts held?").fill("Licence not yet shown");
    await admin.getByRole("button", { name: "Hold payouts" }).click();
    await expect(admin.getByTestId("frozen")).toContainText("Licence not yet shown");

    // The same admin asks for an adjustment but cannot approve it.
    await admin.getByRole("link", { name: "Adjustments" }).click();
    await admin.getByLabel("Business id").fill(merchantId);
    await admin.getByLabel("Amount (−12.50 takes)").fill("10.00");
    await admin.getByLabel("Reason").fill("Goodwill after an outage");
    await admin.getByRole("button", { name: "Ask for approval" }).click();
    const adjustment = admin.getByTestId("adjustment-row").filter({ hasText: merchantId });
    await expect(adjustment.getByTestId("adjustment-status")).toHaveText("pending");
    await expect(adjustment).toContainText("waiting for a second admin");
    await expect(adjustment.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await admin.context().close();

    // A second admin approves, and the money is in the merchant's balance.
    const second = await staff(browser, "admin2@cutpay.test");
    await second.getByRole("link", { name: "Adjustments" }).click();
    const pending = second.getByTestId("adjustment-row").filter({ hasText: merchantId });
    await pending.getByRole("button", { name: "Approve" }).click();
    await expect(pending.getByTestId("adjustment-status")).toHaveText("approved");

    await nav(page).getByRole("link", { name: "Balances" }).click();
    await expect(async () => {
      await page.reload();
      await expect(page.getByTestId("balance-available")).toHaveText("€10.00", { timeout: 2_000 });
    }).toPass({ timeout: 45_000, intervals: [1_000, 2_000, 3_000] });

    // Staff actions are all in the audit.
    await second.getByRole("link", { name: "Audit" }).click();
    for (const action of ["staff.application_approve", "staff.payouts_frozen"]) {
      await expect(second.getByTestId("audit-row").filter({ hasText: action }).first()).toBeVisible();
    }
    await second.context().close();
  });
});
