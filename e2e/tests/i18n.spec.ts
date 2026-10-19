import { mkdirSync, writeFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import { signUp, startCheckout } from "./support";

/** What the checkout says in each language: the heading of the card form, the tabs, and the pay button. */
const LOCALES = {
  en: { card: "Pay by card", tabs: ["Card", "Wallet", "BLIK"], pay: /^Pay / },
  bg: { card: "Плащане с карта", tabs: ["Карта", "Портфейл", "BLIK"], pay: /^Плати / },
  ro: { card: "Plată cu cardul", tabs: ["Card", "Portofel", "BLIK"], pay: /^Plătește / },
  pl: { card: "Płatność kartą", tabs: ["Karta", "Portfel", "BLIK"], pay: /^Zapłać / },
  hu: { card: "Fizetés kártyával", tabs: ["Kártya", "Tárca", "BLIK"], pay: /^Fizetés: / },
  el: { card: "Πληρωμή με κάρτα", tabs: ["Κάρτα", "Πορτοφόλι", "BLIK"], pay: /^Πληρωμή / },
} as const;

/** The page must fit the window: a translation that is too long would push it sideways. */
async function expectNoSidewaysScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
}

test.describe("the checkout in six languages", () => {
  test("renders in every language, in a desktop and a phone window, with a screenshot of each", async ({ page, context }, testInfo) => {
    test.setTimeout(180_000);
    await signUp(page, "PL");
    const checkout = await startCheckout(page, context, "120.00", "PLN");
    const url = checkout.url().split("?")[0]!;

    for (const [locale, words] of Object.entries(LOCALES)) {
      for (const size of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
        await checkout.setViewportSize(size);
        await checkout.goto(`${url}?locale=${locale}`);
        await expect(checkout.getByRole("heading", { name: words.card })).toBeVisible({ timeout: 20_000 });
        for (const tab of words.tabs) await expect(checkout.getByRole("tab", { name: tab, exact: true })).toBeVisible();
        await expect(checkout.getByRole("button", { name: words.pay })).toBeVisible();
        await expect(checkout.locator("html")).toHaveAttribute("lang", locale);
        await expectNoSidewaysScroll(checkout);
        const shot = await checkout.screenshot({ fullPage: true });
        await testInfo.attach(`checkout-${locale}-${size.width}`, { body: shot, contentType: "image/png" });
        // Also kept as plain files, to look at without opening the report.
        mkdirSync("test-results/i18n", { recursive: true });
        writeFileSync(`test-results/i18n/checkout-${locale}-${size.width}.png`, shot);
      }
    }
  });

  test("shows the lev amount next to euros in Bulgarian and not in other languages", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page);
    const checkout = await startCheckout(page, context, "25.00", "EUR");
    const url = checkout.url().split("?")[0]!;

    await checkout.goto(`${url}?locale=bg`);
    await expect(checkout.getByTestId("amount")).toContainText("25,00");
    await expect(checkout.getByTestId("amount-bgn")).toContainText("48,90");
    await expect(checkout.getByTestId("amount-bgn")).toContainText("лв.");

    await checkout.goto(`${url}?locale=en`);
    await expect(checkout.getByTestId("amount")).toHaveText("€25.00");
    await expect(checkout.getByTestId("amount-bgn")).toHaveCount(0);
  });

  test("the customer can switch language, it is remembered, and a whole payment works in Polish", async ({ page, context }) => {
    test.setTimeout(120_000);
    await signUp(page, "PL");
    const checkout = await startCheckout(page, context, "75.00", "PLN");
    await expect(checkout.getByRole("heading", { name: "Pay by card" })).toBeVisible();

    await checkout.getByRole("combobox", { name: "Language" }).selectOption("pl");
    await expect(checkout.getByRole("heading", { name: "Płatność kartą" })).toBeVisible();
    await expect(checkout.getByTestId("amount")).toContainText("75,00");

    // The choice stays after a reload.
    await checkout.reload();
    await expect(checkout.getByRole("heading", { name: "Płatność kartą" })).toBeVisible({ timeout: 20_000 });

    const form = checkout.getByRole("form", { name: "Dane płatności" });
    await form.getByLabel("E-mail").fill("anna@example.com");
    await form.getByLabel("Numer karty").fill("4242 4242 4242 4242");
    await form.getByLabel("Ważność").fill("1299");
    await form.getByLabel("CVC").fill("123");
    await form.getByRole("button", { name: /^Zapłać/ }).click();
    await expect(checkout.getByRole("heading", { name: "Płatność udana" })).toBeVisible({ timeout: 20_000 });
  });

  test("the merchant can ask for a language when the payment is created", async ({ page, request }) => {
    test.setTimeout(120_000);
    const signup = await request.post("http://localhost:8080/auth/signup", {
      data: { email: `dev+${Date.now()}@example.com`, password: "correct-horse-battery", full_name: "Dana Developer", business_name: "Demo Shop Ltd", country: "BG" },
    });
    expect(signup.ok()).toBeTruthy();
    const token = ((await signup.json()) as { access_token: string }).access_token;

    const created = await request.post("http://localhost:8080/v1/checkout_sessions", {
      headers: { Authorization: `Bearer ${token}` },
      data: { amount: 1500, currency: "EUR", merchant_name: "Demo Shop", locale: "ro" },
    });
    expect(created.status()).toBe(201);
    const { url } = (await created.json()) as { url: string };

    await page.goto(url);
    await expect(page.getByRole("heading", { name: "Plată cu cardul" })).toBeVisible({ timeout: 20_000 });

    // A language in the link wins over the one the merchant asked for.
    await page.goto(`${url}?locale=el`);
    await expect(page.getByRole("heading", { name: "Πληρωμή με κάρτα" })).toBeVisible({ timeout: 20_000 });
  });
});
