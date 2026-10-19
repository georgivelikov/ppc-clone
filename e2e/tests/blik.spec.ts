import { expect, test } from "@playwright/test";
import { signUp, startCheckout } from "./support";

test.describe("pay with BLIK", () => {
  test("the customer approves in the bank app, the payment succeeds and costs 1.29% + PLN 0.25", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page, "PL");
    const checkout = await startCheckout(page, context, "100.00", "PLN");

    await checkout.getByRole("tab", { name: "BLIK" }).click();
    const form = checkout.getByRole("form", { name: "Pay with BLIK" });
    await form.getByLabel("Email").fill("anna@example.com");
    await form.getByLabel("BLIK code").fill("777123");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Confirm in your banking app" })).toBeVisible();

    // The pretend bank app shows the request; approving it is the customer's job.
    const bank = await context.newPage();
    await bank.goto("http://localhost:5174/vault/sim/blik-app");
    await expect(bank.getByTestId("blik-request").first()).toBeVisible();
    await bank.getByTestId("blik-request").first().getByRole("button", { name: "Approve" }).click();
    // Leave the page only once the bank has taken the answer, or the click may be cut short.
    await expect(bank.getByTestId("blik-request")).toHaveCount(0);
    await bank.close();

    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 20_000 });

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByTestId("method-blik")).toContainText("Paid with BLIK");
    await expect(page.getByTestId("payment-fee")).toContainText("1.54", { timeout: 20_000 });
    await expect(page.getByTestId("payment-net")).toContainText("98.46");
  });

  test("a request that is rejected in the bank app fails the payment", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page, "PL");
    const checkout = await startCheckout(page, context, "40.00", "PLN");

    await checkout.getByRole("tab", { name: "BLIK" }).click();
    const form = checkout.getByRole("form", { name: "Pay with BLIK" });
    await form.getByLabel("Email").fill("anna@example.com");
    await form.getByLabel("BLIK code").fill("777123");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Confirm in your banking app" })).toBeVisible();

    const bank = await context.newPage();
    await bank.goto("http://localhost:5174/vault/sim/blik-app");
    await bank.getByTestId("blik-request").first().getByRole("button", { name: "Reject" }).click();
    await expect(bank.getByTestId("blik-request")).toHaveCount(0);
    await bank.close();

    await expect(checkout.getByRole("heading", { name: "Payment failed" })).toBeVisible({ timeout: 20_000 });
  });

  test("a code the bank does not know can be corrected, and a request nobody answers is approved by itself", async ({
    page,
    context,
  }) => {
    test.setTimeout(120_000);
    await signUp(page, "PL");
    const checkout = await startCheckout(page, context, "25.00", "PLN");

    await checkout.getByRole("tab", { name: "BLIK" }).click();
    const form = checkout.getByRole("form", { name: "Pay with BLIK" });
    await form.getByLabel("Email").fill("anna@example.com");
    await form.getByLabel("BLIK code").fill("123456");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(form.getByText(/That code is not valid or has expired/)).toBeVisible();

    // Nobody answers in the bank app: the pretend bank approves after a few seconds.
    await form.getByLabel("BLIK code").fill("777123");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
  });

  test("a declined code fails the payment, and BLIK is not offered for euro payments", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page, "PL");
    const checkout = await startCheckout(page, context, "25.00", "PLN");
    await checkout.getByRole("tab", { name: "BLIK" }).click();
    const form = checkout.getByRole("form", { name: "Pay with BLIK" });
    await form.getByLabel("Email").fill("anna@example.com");
    await form.getByLabel("BLIK code").fill("000000");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Payment failed" })).toBeVisible({ timeout: 20_000 });

    const euro = await startCheckout(page, context, "25.00", "EUR");
    await expect(euro.getByRole("tab", { name: "Wallet" })).toBeVisible();
    await expect(euro.getByRole("tab", { name: "BLIK" })).toHaveCount(0);
  });
});
