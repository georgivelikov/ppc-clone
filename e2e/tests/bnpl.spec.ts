import { expect, test, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });
const SIM = "http://localhost:8089";

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Sofa Studio");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Sofa Studio");
}

/** Creates a test payment in the dashboard and returns the hosted checkout opened in a new tab. */
async function startCheckout(page: Page, context: BrowserContext, amount: string): Promise<Page> {
  await nav(page).getByRole("link", { name: "Payments" }).click();
  await page.getByRole("button", { name: "Create test payment" }).click();
  await page.getByLabel("Amount").fill(amount);
  await page.getByRole("button", { name: "Create checkout" }).click();
  await expect(page.getByRole("dialog", { name: "Checkout is ready" })).toBeVisible();

  const [checkout] = await Promise.all([
    context.waitForEvent("page"),
    page.getByRole("link", { name: "Open checkout" }).click(),
  ]);
  await expect(checkout.getByRole("tab", { name: "Pay in instalments" })).toBeVisible({ timeout: 20_000 });
  return checkout;
}

/** The form of the instalments tab; the card form is in the page too, with fields of the same names. */
const form = (checkout: Page) => checkout.getByRole("form", { name: "Pay in instalments" });

async function openInstalments(checkout: Page) {
  await checkout.getByRole("tab", { name: "Pay in instalments" }).click();
  await expect(form(checkout)).toBeVisible();
}

/** A birthdate that Lendo's credit score accepts: the same sum the simulator uses (see ADR 0017). */
function approvedBirthdate(): string {
  for (let day = 1; day <= 28; day++) {
    const score = 400 + ((day * 13 + 1 * 29 + (90 % 100) * 7) % 450);
    if (score >= 600) return `1990-01-${String(day).padStart(2, "0")}`;
  }
  throw new Error("no accepted birthdate in January 1990");
}

async function simulator(request: APIRequestContext, provider: string, settings: Record<string, unknown>) {
  const response = await request.put(`${SIM}/admin/providers/${provider}`, { data: settings });
  expect(response.ok()).toBeTruthy();
}

test.describe("pay in instalments", () => {
  // PayLaterEU hangs for a fifth of its calls by default; a test that depends on its offer must not be at its mercy.
  test.beforeEach(async ({ request }) => {
    await simulator(request, "paylatereu", { timeout_rate: 0, outage: false });
    await simulator(request, "lendo", { decision_delay_ms: 1000 });
  });

  test.afterEach(async ({ request }) => {
    await request.post(`${SIM}/admin/reset`);
  });

  test("pays €300 in three instalments, the merchant is paid up front and a refund goes through the provider", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "300.00");
    await expect(checkout.getByTestId("amount")).toHaveText("€300.00");

    await openInstalments(checkout);
    await expect(form(checkout).getByText("SplitNow: 3 payments of €100.00 every 2 weeks")).toBeVisible();
    await expect(form(checkout).getByText("No interest · total €300.00").first()).toBeVisible();
    await expect(form(checkout).getByText("Lendo: 12 payments of")).toBeVisible();
    await expect(form(checkout).getByText("PayLaterEU: €300.00 in 30 days")).toBeVisible();

    await form(checkout).getByRole("radio", { name: /SplitNow: 3 payments/ }).check();
    await form(checkout).getByLabel("Email").fill("anna@example.com");
    await form(checkout).getByRole("button", { name: /^Apply for/ }).click();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });

    // The merchant sees who financed it and is paid the price less the provider's fee: 3.5% of €300.
    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByTestId("method-instalments")).toContainText("Paid in instalments");
    await expect(page.getByTestId("bnpl-fee")).toHaveText("−€10.50");
    await expect(page.getByTestId("bnpl-net")).toHaveText("€289.50");
    await expect(page.getByTestId("payment-fee")).toHaveText("−€10.50", { timeout: 20_000 });
    await expect(page.getByTestId("payment-net")).toHaveText("€289.50");
    await expect(page.getByTestId("card-summary")).toHaveCount(0);

    // A refund is the provider's to agree to; it shortens the customer's instalments.
    await page.getByRole("button", { name: "Refund" }).click();
    await page.getByLabel(/Refund amount/).fill("100.00");
    await page.getByRole("dialog").getByRole("button", { name: "Refund" }).click();
    await expect(page.getByText("Partially refunded", { exact: true })).toBeVisible();

    // The Instalments page shows the application, its fee and how the provider has done.
    await nav(page).getByRole("link", { name: "Instalments" }).click();
    const application = page.getByTestId("bnpl-application").first();
    await expect(application.getByText("SplitNow")).toBeVisible();
    await expect(application.getByText("3 × €100.00")).toBeVisible();
    await expect(application.getByText("€10.50")).toBeVisible();
    await expect(application.getByText("Approved", { exact: true })).toBeVisible();
    await expect(page.getByTestId("analytics-splitnow")).toContainText("100%");
    await expect(page.getByTestId("analytics-splitnow")).toContainText("Working");
  });

  test("a declined customer chooses another provider and pays", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "300.00");
    await openInstalments(checkout);

    // SplitNow turns away this address (it is on its blocklist).
    await form(checkout).getByRole("radio", { name: /SplitNow: 3 payments/ }).check();
    await form(checkout).getByLabel("Email").fill("fraud@example.com");
    await form(checkout).getByRole("button", { name: /^Apply for/ }).click();
    await expect(checkout.getByRole("heading", { name: "That did not work out" })).toBeVisible({ timeout: 30_000 });
    await expect(checkout.getByText(/could not approve this application/)).toBeVisible();

    await checkout.getByRole("button", { name: "Choose another option" }).click();
    await form(checkout).getByRole("radio", { name: /PayLaterEU: €300.00 in 30 days/ }).check();
    await form(checkout).getByLabel("Email").fill("anna@example.com");
    await form(checkout).getByRole("button", { name: /^Apply for/ }).click();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });

    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByTestId("bnpl-fee")).toHaveText("−€7.50");
    await expect(page.getByTestId("bnpl-net")).toHaveText("€292.50");
    await nav(page).getByRole("link", { name: "Instalments" }).click();
    await expect(page.getByTestId("bnpl-application")).toHaveCount(2);
    await expect(page.getByTestId("analytics-splitnow")).toContainText("1 declined");
  });

  test("a provider that decides later is waited for, and its answer completes the payment", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "300.00");
    await openInstalments(checkout);

    await form(checkout).getByRole("radio", { name: /Lendo: 12 payments/ }).check();
    await form(checkout).getByLabel("Email").fill("anna@example.com");
    await form(checkout).getByLabel("Date of birth").fill(approvedBirthdate());
    await form(checkout).getByRole("button", { name: /^Apply for/ }).click();

    await expect(checkout.getByRole("heading", { name: "Waiting for Lendo" })).toBeVisible();
    // Lendo calls back with a signed message a moment later.
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 45_000 });

    await page.getByRole("link", { name: "View payment" }).click();
    // Lendo keeps 2.9% of €300.
    await expect(page.getByTestId("bnpl-fee")).toHaveText("−€8.70");
    await expect(page.getByTestId("bnpl-net")).toHaveText("€291.30");
  });

  test("changing the settings changes which provider is recommended and the explanation says why", async ({ page, context }) => {
    test.setTimeout(150_000);
    await signUp(page);
    await nav(page).getByRole("link", { name: "Instalments" }).click();
    await page.getByLabel("How the provider is chosen").selectOption("cheapest");
    await expect(page.getByLabel("How the provider is chosen")).toHaveValue("cheapest");

    const checkout = await startCheckout(page, context, "300.00");
    await openInstalments(checkout);
    await expect(form(checkout).getByRole("radio", { name: /Best option/ })).toBeChecked();

    // The merchant sees the reasoning on the payment.
    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByTestId("bnpl-reason")).toHaveText("Chose PayLaterEU: cheapest eligible (2.5% vs 2.9% for Lendo)");

    // Switch PayLaterEU off: the next time offers are worked out, Lendo is the cheapest.
    await nav(page).getByRole("link", { name: "Instalments" }).click();
    // The switch follows the server, so click it and wait for the answer rather than expecting it to flip at once.
    await page.getByRole("checkbox", { name: "Offer PayLaterEU" }).click();
    await expect(page.getByRole("checkbox", { name: "Offer PayLaterEU" })).not.toBeChecked();
    await checkout.reload();
    await expect(checkout.getByRole("tab", { name: "Pay in instalments" })).toBeVisible({ timeout: 20_000 });
    await nav(page).getByRole("link", { name: "Payments" }).click();
    await page.getByRole("link", { name: "€300.00" }).first().click();
    await expect(page.getByTestId("bnpl-reason")).toHaveText(
      "Chose Lendo: cheapest eligible (2.9% vs 3.5% for SplitNow); PayLaterEU excluded: switched off in your settings",
    );
  });

  test("a provider that is down is left out and comes back by itself", async ({ page, context, request }) => {
    test.setTimeout(150_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "300.00");
    const secret = new URL(checkout.url()).pathname.split("/").pop()!;
    await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();

    await simulator(request, "paylatereu", { outage: true });
    // The circuit of a provider opens after a few failed calls; each look at the offers is one call.
    for (let i = 0; i < 6; i++) {
      const offers = await request.get(`http://localhost:8080/checkout/${secret}/bnpl/offers`);
      expect(offers.ok()).toBeTruthy();
    }
    const down = await (await request.get(`http://localhost:8080/checkout/${secret}/bnpl/offers`)).json();
    expect(down.offers.map((offer: { provider: string }) => offer.provider)).not.toContain("paylatereu");

    await nav(page).getByRole("link", { name: "Instalments" }).click();
    await expect(page.getByTestId("analytics-paylatereu")).toContainText("Down");

    // The provider recovers; after the circuit's waiting time one request is the probe.
    await simulator(request, "paylatereu", { outage: false });
    await expect(async () => {
      const offers = await (await request.get(`http://localhost:8080/checkout/${secret}/bnpl/offers`)).json();
      expect(offers.offers.map((offer: { provider: string }) => offer.provider)).toContain("paylatereu");
    }).toPass({ timeout: 40_000, intervals: [2_000] });
    // The circuit lets two probes through before it trusts the provider again.
    await request.get(`http://localhost:8080/checkout/${secret}/bnpl/offers`);
    await page.reload();
    await page.waitForLoadState("networkidle");
    await expect(page.getByTestId("analytics-paylatereu")).toContainText("Working");
  });
});
