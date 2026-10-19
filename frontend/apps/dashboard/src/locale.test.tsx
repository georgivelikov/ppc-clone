import type { AuthResponse } from "@cutpay/api-client";
import { dashboardResources, setupI18n, STORAGE_KEY } from "@cutpay/i18n";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthProvider";

vi.mock("./api", () => ({
  api: {
    restoreSession: vi.fn(),
    logout: vi.fn(),
    paymentSummary: vi.fn().mockResolvedValue({ since: "2026-01-01T00:00:00Z", today: [{ currency: "EUR", count: 2, volume: 123456 }] }),
    subscriptionStats: vi.fn().mockResolvedValue({ mrr: {}, by_status: {}, ended_last_30_days: 0 }),
    listPayments: vi.fn().mockResolvedValue({ data: [], has_more: false }),
    getBalance: vi.fn().mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [],
      available: [{ amount: 12345, currency: "RON" }],
      reserved: [],
    }),
    balanceSummary: vi.fn().mockResolvedValue({ object: "list", data: [] }),
    listBalanceTransactions: vi.fn().mockResolvedValue({ object: "list", data: [], has_more: false }),
    getFxReport: vi.fn().mockResolvedValue({ object: "fx_report", days: 30, data: [] }),
    listFxConversions: vi.fn().mockResolvedValue({ object: "list", data: [], has_more: false }),
    getCurrencySettings: vi.fn().mockResolvedValue({ object: "currency_settings", settlement_currencies: ["EUR"], auto_convert: false, fx_markup_bps: 100 }),
    getPaymentMethodSettings: vi.fn().mockResolvedValue({
      object: "payment_method_settings",
      data: [
        { method: "card", enabled: true, locked: true },
        { method: "blik", enabled: true, locked: false },
      ],
    }),
    createFxQuote: vi.fn(),
    getMerchant: vi.fn().mockResolvedValue({ id: "mer_1", display_name: "Rose Cafe", legal_name: "Rose Cafe", country: "BG", default_currency: "EUR", status: "active" }),
    getSecurity: vi.fn().mockResolvedValue({ object: "security", email_verified: true, totp_enabled: false, recovery_codes_left: 0 }),
    listConnectedAccounts: vi.fn().mockResolvedValue({ object: "list", data: [], has_more: false }),
  },
  onSessionExpired: vi.fn(),
}));

const { api } = await import("./api");

const session: AuthResponse = {
  access_token: "tok",
  token_type: "Bearer",
  expires_in: 900,
  user: { id: "usr_1", email: "ivan@example.com", full_name: "Ivan Petrov" },
  merchant: { id: "mer_1", display_name: "Rose Cafe", legal_name: "Rose Cafe", country: "BG", default_currency: "EUR", status: "pending_kyc" },
  role: "owner",
};

function renderApp(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** A message key that is missing everywhere shows as itself, such as "balances.cols.net". */
const KEY_LEAK = /\b(nav|shell|home|payments|balances|fx|methods|status|settingsTabs)\.[a-zA-Z_.]+/;

describe("the dashboard in Bulgarian", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.restoreSession).mockResolvedValue(session);
    setupI18n({ resources: dashboardResources, locale: "bg", defaultNamespace: "dashboard" });
  });

  it("translates the navigation, the header and the home page", async () => {
    renderApp("/");
    expect(await screen.findByRole("heading", { name: "Добре дошли, Ivan" })).toBeInTheDocument();
    const nav = screen.getByRole("navigation", { name: "Основно меню" });
    for (const label of ["Начало", "Плащания", "Баланси", "Изплащания", "Настройки"]) {
      expect(within(nav).getByRole("link", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole("button", { name: "Изход" })).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Тестов режим" })).toBeInTheDocument();
    expect(screen.getByText("Виждате тестови данни. Не са замесени реални пари.")).toBeInTheDocument();
    // Bulgarian writes amounts with a decimal comma and the symbol after.
    expect(await screen.findByTestId("today-volume")).toHaveTextContent(/^1\s?234,56\s€$/);
    expect(document.body.textContent).not.toMatch(KEY_LEAK);
  });

  it("translates the payments list, with the statuses in the filter", async () => {
    renderApp("/payments");
    expect(await screen.findByRole("heading", { name: "Плащания" })).toBeInTheDocument();
    const filter = screen.getByRole("combobox", { name: "Филтър по статус" });
    expect(within(filter).getByRole("option", { name: "Всички статуси" })).toBeInTheDocument();
    expect(within(filter).getByRole("option", { name: "Частично възстановено" })).toBeInTheDocument();
    expect(await screen.findByText(/Още няма плащания/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(KEY_LEAK);
  });

  it("translates the balances, the conversion and the currency settings", async () => {
    renderApp("/balances");
    expect(await screen.findByRole("heading", { name: "Баланси" })).toBeInTheDocument();
    const row = await screen.findByTestId("balance-RON");
    expect(row).toHaveTextContent(/123,45\sRON налични/);
    await userEvent.click(within(row).getByRole("button", { name: "Обмени RON" }));
    const dialog = await screen.findByRole("dialog", { name: "Обмяна на RON" });
    expect(within(dialog).getByLabelText("Сума за обмяна")).toHaveValue("123.45");
    expect(within(dialog).getByRole("button", { name: "Поискай оферта" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Отказ" }));

    expect(await screen.findByRole("form", { name: "Валутни настройки" })).toBeInTheDocument();
    expect(screen.getByTestId("fx-markup")).toHaveTextContent("Обменът струва 1% над пазарния курс");
    expect(document.body.textContent).not.toMatch(KEY_LEAK);
  });

  it("translates the payment method settings", async () => {
    renderApp("/settings/methods");
    expect(await screen.findByRole("heading", { name: "Начини на плащане" })).toBeInTheDocument();
    expect(await screen.findByRole("checkbox", { name: "Приемай BLIK" })).toBeInTheDocument();
    // One link in the navigation and one in the text.
    expect(screen.getAllByRole("link", { name: "Вноски" })).toHaveLength(2);
    expect(document.body.textContent).not.toMatch(KEY_LEAK);
  });

  it("switches language from the header and remembers it", async () => {
    setupI18n({ resources: dashboardResources, locale: "en", defaultNamespace: "dashboard" });
    renderApp("/payments");
    expect(await screen.findByRole("heading", { name: "Payments" })).toBeInTheDocument();
    const switcher = screen.getByRole("combobox", { name: "Language" });
    // Only the languages the dashboard has are offered.
    expect(within(switcher).getAllByRole("option").map((option) => option.getAttribute("value"))).toEqual(["en", "bg"]);

    await userEvent.selectOptions(switcher, "bg");
    expect(await screen.findByRole("heading", { name: "Плащания" })).toBeInTheDocument();
    expect(localStorage.getItem(STORAGE_KEY)).toBe("bg");
    expect(screen.getByRole("combobox", { name: "Език" })).toHaveValue("bg");
  });
});
