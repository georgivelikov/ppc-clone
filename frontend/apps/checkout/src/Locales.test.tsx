import { checkoutResources, LOCALES, setupI18n, STORAGE_KEY } from "@cutpay/i18n";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Session } from "./api";
import { CheckoutPage } from "./CheckoutPage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSession: vi.fn(),
  getMethods: vi.fn(),
}));
vi.mock("./bnplApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./bnplApi")>()),
  getBnplOffers: vi.fn(),
}));

const api = await import("./api");
const bnplApi = await import("./bnplApi");

const base: Session = {
  id: "pay_1",
  status: "requires_payment_method",
  amount: 2500,
  currency: "EUR",
  description: "Flat white x2",
  merchant_name: "Rose Cafe",
  return_url: null,
  failure_code: null,
  failure_message: null,
  next_action: null,
};

function renderCheckout(path = "/c/pay_1_secret_x") {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/c/:secret" element={<CheckoutPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** What the card form is called in each language: the proof that the page was translated, not just the switcher. */
const CARD_TITLE = {
  en: "Pay by card",
  bg: "Плащане с карта",
  ro: "Plată cu cardul",
  pl: "Płatność kartą",
  hu: "Fizetés kártyával",
  el: "Πληρωμή με κάρτα",
} as const;

describe("the checkout in every language", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
    vi.mocked(api.getSession).mockResolvedValue(base);
    vi.mocked(api.getMethods).mockResolvedValue({ methods: ["card", "apple_pay", "google_pay"] });
    vi.mocked(bnplApi.getBnplOffers).mockRejectedValue(new Error("none"));
  });

  it.each(LOCALES)("%s: the page is translated, and no message key shows through", async (locale) => {
    setupI18n({ resources: checkoutResources, locale, defaultNamespace: "checkout" });
    renderCheckout();

    expect(await screen.findByRole("heading", { name: CARD_TITLE[locale] })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe(locale);
    // A key that is missing everywhere is shown as itself, such as "card.errors.cvc".
    expect(document.body.textContent).not.toMatch(/\b(card|wallet|blik|bnpl|methods|outcome|threeDs|page|shell)\.[a-zA-Z_.]+/);
  });

  it("switches language from the page and remembers the choice", async () => {
    renderCheckout();
    expect(await screen.findByRole("heading", { name: "Pay by card" })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Language" }), "pl");

    expect(await screen.findByRole("heading", { name: "Płatność kartą" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Język" })).toHaveValue("pl");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("pl");
    expect(screen.getByRole("button", { name: /^Zapłać/ })).toBeInTheDocument();
  });

  it("checks the card form in the language shown", async () => {
    setupI18n({ resources: checkoutResources, locale: "ro", defaultNamespace: "checkout" });
    renderCheckout();
    await userEvent.click(await screen.findByRole("button", { name: /^Plătește/ }));
    expect(await screen.findByText("Introduceți codul de securitate")).toBeInTheDocument();
    expect(screen.getByText("Introduceți un număr de card valid")).toBeInTheDocument();
  });

  it("opens in the language the merchant asked for, unless the customer chose one", async () => {
    vi.mocked(api.getSession).mockResolvedValue({ ...base, locale: "hu" });
    renderCheckout();
    expect(await screen.findByRole("heading", { name: "Fizetés kártyával" })).toBeInTheDocument();
  });

  it("keeps the customer's own choice over the merchant's", async () => {
    localStorage.setItem(STORAGE_KEY, "el");
    setupI18n({ resources: checkoutResources, locale: "el", defaultNamespace: "checkout" });
    vi.mocked(api.getSession).mockResolvedValue({ ...base, locale: "hu" });
    renderCheckout();
    expect(await screen.findByRole("heading", { name: "Πληρωμή με κάρτα" })).toBeInTheDocument();
    await waitFor(() => expect(document.documentElement.lang).toBe("el"));
  });

  it("shows the price in the language's own format, and the lev amount next to euros in Bulgarian", async () => {
    setupI18n({ resources: checkoutResources, locale: "bg", defaultNamespace: "checkout" });
    renderCheckout();
    expect(await screen.findByTestId("amount")).toHaveTextContent(/^25,00\s€$/);
    expect(screen.getByTestId("amount-bgn")).toHaveTextContent(/≈ 48,90\sлв\./);
  });

  it("leaves the lev amount out for other currencies and languages", async () => {
    vi.mocked(api.getSession).mockResolvedValue({ ...base, currency: "PLN" });
    setupI18n({ resources: checkoutResources, locale: "bg", defaultNamespace: "checkout" });
    renderCheckout();
    await screen.findByTestId("amount");
    expect(screen.queryByTestId("amount-bgn")).not.toBeInTheDocument();
  });

  it("writes a payment of three instalments with each language's plural form", async () => {
    const { planText } = await import("./bnpl");
    const offer = { instalments: 3, instalment_amounts: [1000, 1000, 1000], interval_days: 14 };
    setupI18n({ resources: checkoutResources, locale: "pl", defaultNamespace: "checkout" });
    expect(planText(offer, "PLN")).toMatch(/^3 raty po 10,00\szł co 2 tygodnie$/);
    expect(planText({ ...offer, instalments: 6, instalment_amounts: Array(6).fill(1000) }, "PLN")).toMatch(/^6 rat po/);
    setupI18n({ resources: checkoutResources, locale: "ro", defaultNamespace: "checkout" });
    expect(planText({ ...offer, instalments: 24, instalment_amounts: Array(24).fill(1000), interval_days: 30 }, "RON")).toMatch(
      /^24 de rate de .* în fiecare lună$|^24 de rate de .* la fiecare 1 de luni$/,
    );
  });
});
