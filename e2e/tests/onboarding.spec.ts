import { expect, test, type Page } from "@playwright/test";
import { inbox, signUp } from "./support";

const SAMPLE = { name: "sample.png", mimeType: "image/png", buffer: Buffer.from("sample image, not a real document") };

const steps = (page: Page) => page.getByRole("navigation", { name: "Steps" });

async function addPerson(page: Page, role: string, name: string, documentNumber: string, share?: string) {
  const form = page.getByRole("form", { name: "Add a person" });
  await form.getByLabel("Role").selectOption(role);
  await form.getByLabel("Full name").fill(name);
  await form.getByLabel("Date of birth").fill("1985-04-12");
  await form.getByLabel("Nationality (2 letters)").fill("BG");
  if (share) await form.getByLabel("Share owned (%)").fill(share);
  await form.getByLabel("Document number").fill(documentNumber);
  await form.getByRole("button", { name: "Add person" }).click();
  await expect(page.getByTestId(`person-${name}`)).toBeVisible();
}

/** Walks the wizard from the banner to the submit button, using made-up people and sample files. */
async function fillAndSubmit(page: Page, ownerDocument: string) {
  await page.getByTestId("application-banner").getByRole("link", { name: "Open the application" }).click();
  await expect(page.getByTestId("application-state")).toHaveText("Not submitted");

  await page.getByLabel("Industry").selectOption("hospitality");
  await page.getByLabel("Registration number").fill("123456789");
  await page.getByRole("button", { name: "Look up" }).click();
  await expect(page.getByTestId("company-found")).toBeVisible();
  await page.getByRole("button", { name: "Save business" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved" })).toBeVisible();

  await steps(page).getByRole("button", { name: "People" }).click();
  await addPerson(page, "representative", "Ivan Petrov", "AB123451");
  await page.getByRole("button", { name: "Add a person" }).click();
  await addPerson(page, "ubo", "Maria Ivanova", ownerDocument, "60");

  await steps(page).getByRole("button", { name: "Documents" }).click();
  for (const person of ["Ivan Petrov", "Maria Ivanova"]) {
    await page.getByLabel(`ID, front for ${person}`).setInputFiles(SAMPLE);
    await page.getByLabel(`Selfie for ${person}`).setInputFiles(SAMPLE);
  }
  await expect(page.getByText("sample.png")).toHaveCount(4);

  await steps(page).getByRole("button", { name: "Bank account" }).click();
  await page.getByLabel("IBAN").fill("BG80 BNBG 9661 1020 3456 78");
  await page.getByRole("button", { name: "Save bank account" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved" })).toBeVisible();
  await page.getByLabel("Proof of the account").setInputFiles(SAMPLE);
  await expect(page.getByText("sample.png")).toBeVisible();

  await steps(page).getByRole("button", { name: "About your sales" }).click();
  await page.getByLabel("What do you sell, and to whom?").fill("A cafe in Sofia selling coffee and cakes");
  await page.getByLabel("Expected sales per month (in euros)").fill("20000");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved" })).toBeVisible();

  await steps(page).getByRole("button", { name: "Review and submit" }).click();
  await expect(page.getByText("Everything is filled in.")).toBeVisible();
  await page.getByLabel(/I confirm that the answers are true/).check();
  await page.getByLabel("Type your full name to sign").fill("Ivan Petrov");
  await page.getByRole("button", { name: "Submit application" }).click();
}

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
