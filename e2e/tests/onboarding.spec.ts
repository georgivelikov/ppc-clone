import { expect, test } from "@playwright/test";
import { inbox, signUp } from "./support";

import { fillAndSubmit, steps } from "./support";

test.describe("onboarding and KYC", () => {
  test("a document that needs a better photo is fixed, and the application is then approved", async ({ page, request }) => {
    test.setTimeout(240_000);
    const email = await signUp(page);
    await expect(page.getByTestId("application-banner")).toContainText("Finish your application");

    await fillAndSubmit(page, "AB123459"); // ends in 9: the photo is not clear enough
    await expect(page.getByTestId("application-state")).toHaveText("In review");
    await expect(page.getByTestId("application-state")).toHaveText("More information needed", { timeout: 45_000 });
    await expect(page.getByTestId("decision-reason")).toContainText("not clear enough");
    const mail = await inbox(request, email, /We need a little more from you/);
    expect(mail.html).toContain("Maria Ivanova");

    // Send a clearer document (a new number and file) and submit again.
    await steps(page).getByRole("button", { name: "People" }).click();
    await page.getByRole("button", { name: "Edit" }).nth(1).click();
    await page.getByLabel("Document number").fill("AB123456");
    await page.getByRole("button", { name: "Save person" }).click();
    await steps(page).getByRole("button", { name: "Review and submit" }).click();
    await page.getByLabel(/I confirm that the answers are true/).check();
    await page.getByLabel("Type your full name to sign").fill("Ivan Petrov");
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(page.getByTestId("application-state")).toHaveText("Approved", { timeout: 45_000 });
    await expect(page.getByTestId("application-banner")).toHaveCount(0, { timeout: 15_000 });
    await inbox(request, email, /is approved/);
  });

  test("a document that is not genuine rejects the application and says why", async ({ page }) => {
    test.setTimeout(240_000);
    await signUp(page);
    await fillAndSubmit(page, "AB123450"); // ends in 0: rejected
    await expect(page.getByTestId("application-state")).toHaveText("Not approved", { timeout: 45_000 });
    await expect(page.getByTestId("decision-reason")).toContainText("could not be verified as genuine");
    // Nothing can be edited any more.
    await steps(page).getByRole("button", { name: "Bank account" }).click();
    await expect(page.getByRole("button", { name: "Save bank account" })).toBeDisabled();
  });
});
