import { expect, test } from "@playwright/test";
import { signUp, startCheckout } from "./support";

test.describe("pay with a wallet", () => {
  test("a card that asks for 3-D Secure is paid without the challenge, and the dashboard names the wallet", async ({
    page,
    context,
  }) => {
    test.setTimeout(120_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "30.00");

    await checkout.getByRole("tab", { name: "Wallet" }).click();
    await checkout.getByRole("button", { name: "Pay with Google Pay" }).click();
    const sheet = checkout.getByRole("dialog", { name: "Google Pay (pretend)" });
    await sheet.getByRole("radio", { name: /Visa that asks for 3-D Secure/ }).check();
    await sheet.getByLabel("Email").fill("anna@example.com");
    await sheet.getByRole("button", { name: "Confirm with Face ID or PIN" }).click();

    // The same card typed into the card form would have been sent to the bank's challenge page.
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 20_000 });
    await expect(checkout.getByRole("heading", { name: "Confirm with your bank" })).toHaveCount(0);

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByTestId("method-wallet")).toHaveText("Google Pay");
    await expect(page.getByTestId("card-summary")).toContainText("3220");
    await expect(page.getByText("Done by the wallet")).toBeVisible();
    // Priced as a card: 1.29% + 0.10 of €30.00.
    await expect(page.getByTestId("payment-fee")).toContainText("0.49", { timeout: 20_000 });
  });

  test("the same card typed into the card form still gets the 3-D Secure challenge", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "30.00");
    const form = checkout.getByRole("form", { name: "Payment details" });
    await form.getByLabel("Email").fill("anna@example.com");
    await form.getByLabel("Card number").fill("4000000000003220");
    await form.getByLabel("Expiry").fill("1299");
    await form.getByLabel("CVC").fill("123");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Confirm with your bank" })).toBeVisible({ timeout: 20_000 });
  });
});
