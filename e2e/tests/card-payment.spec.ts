import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Rose Cafe");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Rose Cafe");
}

/** Creates a test payment in the dashboard and returns the hosted checkout opened in a new tab. */
async function startCheckout(page: Page, context: BrowserContext, amount = "25.00"): Promise<Page> {
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Payments" }).click();
  await page.getByRole("button", { name: "Create test payment" }).click();
  await page.getByLabel("Amount").fill(amount);
  await page.getByRole("button", { name: "Create checkout" }).click();
  await expect(page.getByRole("dialog", { name: "Checkout is ready" })).toBeVisible();

  const [checkout] = await Promise.all([
    context.waitForEvent("page"),
    page.getByRole("link", { name: "Open checkout" }).click(),
  ]);
  await expect(checkout.getByRole("heading", { name: "Pay by card" })).toBeVisible();
  return checkout;
}

async function payWithCard(checkout: Page, number: string) {
  await checkout.getByRole("form", { name: "Payment details" }).getByLabel("Email").fill("anna@example.com");
  await checkout.getByLabel("Card number").fill(number);
  await checkout.getByLabel("Expiry").fill("1299");
  await checkout.getByLabel("CVC").fill("123");
  await checkout.getByRole("button", { name: /^Pay/ }).click();
}

test.describe("card payments", () => {
  test("pays with a test card, sees it in the dashboard and refunds it in two parts", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);
    await expect(checkout.getByTestId("amount")).toHaveText("€25.00");

    await payWithCard(checkout, "4242 4242 4242 4242");
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible();

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByTestId("card-summary")).toHaveText("visa •••• 4242");
    await expect(page.getByText("anna@example.com", { exact: true })).toBeVisible();

    // First half.
    await page.getByRole("button", { name: "Refund" }).click();
    await page.getByLabel(/Refund amount/).fill("10.00");
    await page.getByRole("dialog").getByRole("button", { name: "Refund" }).click();
    await expect(page.getByText("Partially refunded", { exact: true })).toBeVisible();

    // The rest. Asking for more than what is left is rejected before it reaches the server.
    await page.getByRole("button", { name: "Refund" }).click();
    await expect(page.getByLabel(/Refund amount/)).toHaveValue("15.00");
    await page.getByLabel(/Refund amount/).fill("20.00");
    await page.getByRole("dialog").getByRole("button", { name: "Refund" }).click();
    await expect(page.getByText("You can refund at most €15.00")).toBeVisible();
    await page.getByLabel(/Refund amount/).fill("15.00");
    await page.getByRole("dialog").getByRole("button", { name: "Refund" }).click();

    await expect(page.getByText("Refunded", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Refund" })).toHaveCount(0);
  });

  test("shows a readable message when the card is declined", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);

    await payWithCard(checkout, "4000 0000 0000 0002");
    await expect(checkout.getByRole("heading", { name: "Payment failed" })).toBeVisible();
    await expect(checkout.getByText("Your card was declined.")).toBeVisible();

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Failed", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Refund" })).toHaveCount(0);
  });

  test("explains insufficient funds", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);

    await payWithCard(checkout, "4000 0000 0000 9995");
    await expect(checkout.getByText("Your card has insufficient funds.")).toBeVisible();
  });

  test("completes a 3-D Secure challenge", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);

    await payWithCard(checkout, "4000 0000 0000 3220");
    await expect(checkout.getByRole("heading", { name: "Confirm with your bank" })).toBeVisible();
    await checkout.getByLabel("One-time code").fill("1234");
    await checkout.getByRole("button", { name: "Approve" }).click();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible();

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByText("authenticated", { exact: true })).toBeVisible();
  });

  test("fails the payment when the 3-D Secure challenge fails", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);

    await payWithCard(checkout, "4000 0000 0000 3220");
    await checkout.getByRole("button", { name: "Fail authentication" }).click();
    await expect(checkout.getByRole("heading", { name: "Payment failed" })).toBeVisible();
    await expect(checkout.getByText("3-D Secure authentication failed.")).toBeVisible();
  });

  test("a paid checkout link cannot be paid twice and the home page counts today's volume", async ({
    page,
    context,
  }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context, "40.00");
    await payWithCard(checkout, "5555 5555 5555 4444");
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible();

    // Opening the same link again shows the result, not the card form.
    await checkout.reload();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible();
    await expect(checkout.getByLabel("Card number")).toHaveCount(0);

    await page.getByRole("button", { name: "Close" }).click();
    await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Home" }).click();
    await expect(page.getByTestId("today-volume")).toHaveText("€40.00");
    await expect(page.getByTestId("today-count")).toHaveText("1");
  });

  test("rejects an invalid card number without leaving the form", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);

    await payWithCard(checkout, "4242 4242 4242 4241");
    await expect(checkout.getByText("Enter a valid card number")).toBeVisible();
    await expect(checkout.getByRole("heading", { name: "Pay by card" })).toBeVisible();
  });

  test("filters the payments list by status", async ({ page, context }) => {
    await signUp(page);
    const checkout = await startCheckout(page, context);
    await payWithCard(checkout, "4000 0000 0000 0002");
    await expect(checkout.getByRole("heading", { name: "Payment failed" })).toBeVisible();

    await page.getByRole("button", { name: "Close" }).click();
    await page.getByLabel("Filter by status").selectOption("succeeded");
    await expect(page.getByText("No payments match these filters.")).toBeVisible();
    await page.getByLabel("Filter by status").selectOption("failed");
    await expect(page.getByRole("link", { name: "€25.00" })).toBeVisible();
  });
});
