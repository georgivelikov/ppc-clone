import type { CurrencySettings, FxConversion, FxQuote } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BalancesPage } from "../BalancesPage";
import { CurrencySettingsCard } from "./CurrencySettingsCard";
import { FxReportCard } from "./FxReportCard";

vi.mock("../../api", () => ({
  api: {
    getBalance: vi.fn(),
    balanceSummary: vi.fn(),
    listBalanceTransactions: vi.fn(),
    getFxReport: vi.fn(),
    listFxConversions: vi.fn(),
    getCurrencySettings: vi.fn(),
    updateCurrencySettings: vi.fn(),
    createFxQuote: vi.fn(),
    convertBalance: vi.fn(),
  },
}));
vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const settings: CurrencySettings = {
  object: "currency_settings",
  settlement_currencies: ["EUR"],
  auto_convert: false,
  fx_markup_bps: 100,
};

const conversion: FxConversion = {
  id: "fxc_1",
  object: "fx_conversion",
  from_currency: "RON",
  to_currency: "EUR",
  from_amount: 49345,
  mid_amount: 9928,
  to_amount: 9829,
  markup_amount: 99,
  rate: 0.201207243461,
  markup_bps: 100,
  kind: "manual",
  quote_id: "fxq_1",
  source_id: null,
  created: "2026-10-19T10:00:00Z",
};

function quote(secondsFromNow: number): FxQuote {
  return {
    id: "fxq_1",
    object: "fx_quote",
    from_currency: "RON",
    to_currency: "EUR",
    from_amount: 49345,
    mid_amount: 9928,
    to_amount: 9829,
    markup_amount: 99,
    rate: 0.201207243461,
    markup_bps: 100,
    expires_at: new Date(Date.now() + secondsFromNow * 1000).toISOString(),
    expires_in: secondsFromNow,
    livemode: false,
  };
}

function wrap(ui: React.ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("converting money", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.balanceSummary).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.getFxReport).mockResolvedValue({ object: "fx_report", days: 30, data: [] });
    vi.mocked(api.listFxConversions).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.getCurrencySettings).mockResolvedValue(settings);
    vi.mocked(api.getBalance).mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [{ amount: 1000, currency: "RON" }],
      available: [
        { amount: 0, currency: "EUR" },
        { amount: 49345, currency: "RON" },
      ],
      reserved: [],
    });
  });

  it("shows each currency with its own balance and a convert button only where there is money to convert", async () => {
    wrap(<BalancesPage />);
    const row = await screen.findByTestId("balance-RON");
    expect(row).toHaveTextContent("RON 493.45 available");
    expect(within(row).getByRole("button", { name: "Convert RON" })).toBeInTheDocument();
    // The euro balance is empty, so it cannot be converted.
    expect(screen.queryByRole("button", { name: "Convert EUR" })).not.toBeInTheDocument();
  });

  it("locks a rate in a quote, shows what arrives and the markup, and converts at that quote", async () => {
    vi.mocked(api.createFxQuote).mockResolvedValue(quote(900));
    vi.mocked(api.convertBalance).mockResolvedValue(conversion);
    wrap(<BalancesPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Convert RON" }));

    const dialog = await screen.findByRole("dialog", { name: "Convert RON" });
    expect(within(dialog).getByLabelText("Amount to convert")).toHaveValue("493.45");
    await userEvent.click(within(dialog).getByRole("button", { name: "Get a quote" }));

    expect(api.createFxQuote).toHaveBeenCalledWith({ from_currency: "RON", to_currency: "EUR", amount: 49345 }, false);
    expect(await within(dialog).findByTestId("quote-receive")).toHaveTextContent("€98.29");
    expect(within(dialog).getByText("−€0.99")).toBeInTheDocument();
    expect(within(dialog).getByText("1 RON = 0.201207 EUR")).toBeInTheDocument();
    expect(within(dialog).getByTestId("quote-timer")).toHaveTextContent(/held for (14:5\d|15:00)/);

    await userEvent.click(within(dialog).getByRole("button", { name: "Convert" }));
    expect(api.convertBalance).toHaveBeenCalledWith(
      { from_currency: "RON", to_currency: "EUR", amount: 49345, quote_id: "fxq_1" },
      false,
    );
    expect(await screen.findByTestId("conversion-result")).toHaveTextContent("RON 493.45 became €98.29");
  });

  it("will not convert more than is available, and says so before asking for a quote", async () => {
    wrap(<BalancesPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Convert RON" }));
    const dialog = await screen.findByRole("dialog", { name: "Convert RON" });
    const amount = within(dialog).getByLabelText("Amount to convert");
    await userEvent.clear(amount);
    await userEvent.type(amount, "900");
    await userEvent.click(within(dialog).getByRole("button", { name: "Get a quote" }));

    expect(await within(dialog).findByText("Only RON 493.45 is available")).toBeInTheDocument();
    expect(api.createFxQuote).not.toHaveBeenCalled();
  });

  it("offers a new quote once the old one has expired", async () => {
    vi.mocked(api.createFxQuote).mockResolvedValue(quote(-5));
    wrap(<BalancesPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Convert RON" }));
    const dialog = await screen.findByRole("dialog", { name: "Convert RON" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Get a quote" }));

    expect(await within(dialog).findByText("This quote has expired.")).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Convert" })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Get a new quote" })).toBeInTheDocument();
  });

  it("tells the merchant when the server has refused an expired quote", async () => {
    const { ApiError } = await import("@cutpay/api-client");
    vi.mocked(api.createFxQuote).mockResolvedValue(quote(600));
    vi.mocked(api.convertBalance).mockRejectedValue(new ApiError(409, "invalid_request_error", "quote_expired", "This quote has expired, ask for a new one"));
    wrap(<BalancesPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Convert RON" }));
    const dialog = await screen.findByRole("dialog", { name: "Convert RON" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Get a quote" }));
    await userEvent.click(await within(dialog).findByRole("button", { name: "Convert" }));

    expect(await within(dialog).findByText("This quote has expired, ask for a new one")).toBeInTheDocument();
    expect(await within(dialog).findByRole("button", { name: "Get a new quote" })).toBeInTheDocument();
  });
});

describe("currency settings", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.getCurrencySettings).mockResolvedValue(settings);
  });

  it("saves the currencies to keep and where converted money goes, first", async () => {
    vi.mocked(api.updateCurrencySettings).mockResolvedValue({
      ...settings,
      settlement_currencies: ["PLN", "EUR"],
      auto_convert: true,
    });
    wrap(<CurrencySettingsCard />);
    const form = await screen.findByRole("form", { name: "Currency settings" });
    // The settings arrive after the form is drawn.
    await vi.waitFor(() => expect(within(form).getByRole("checkbox", { name: "EUR" })).toBeChecked());
    expect(within(form).getByTestId("fx-markup")).toHaveTextContent("1% over the market rate");

    await userEvent.click(within(form).getByRole("checkbox", { name: "PLN" }));
    await userEvent.click(within(form).getByRole("checkbox", { name: "Convert other currencies automatically" }));
    await userEvent.selectOptions(within(form).getByRole("combobox", { name: "Convert into" }), "PLN");
    await userEvent.click(within(form).getByRole("button", { name: "Save currencies" }));

    expect(api.updateCurrencySettings).toHaveBeenCalledWith({ settlement_currencies: ["PLN", "EUR"], auto_convert: true });
    expect(await within(form).findByRole("status")).toHaveTextContent("Saved");
  });

  it("will not save without a currency", async () => {
    wrap(<CurrencySettingsCard />);
    const form = await screen.findByRole("form", { name: "Currency settings" });
    await vi.waitFor(() => expect(within(form).getByRole("checkbox", { name: "EUR" })).toBeChecked());
    await userEvent.click(within(form).getByRole("checkbox", { name: "EUR" }));
    expect(within(form).getByRole("button", { name: "Save currencies" })).toBeDisabled();
  });
});

describe("the conversion report", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("totals what conversions brought in and lists the latest ones", async () => {
    vi.mocked(api.getFxReport).mockResolvedValue({
      object: "fx_report",
      days: 30,
      data: [{ currency: "EUR", conversions: 2, received: 19000, markup: 190 }],
    });
    vi.mocked(api.listFxConversions).mockResolvedValue({
      object: "list",
      data: [conversion, { ...conversion, id: "fxc_2", kind: "auto", source_id: "pay_9" }],
      has_more: false,
    });
    wrap(<FxReportCard />);

    const row = await screen.findByTestId("fx-report-EUR");
    expect(row).toHaveTextContent("2");
    expect(row).toHaveTextContent("€190.00");
    expect(row).toHaveTextContent("€1.90");
    const latest = await screen.findAllByTestId("fx-conversion");
    expect(latest).toHaveLength(2);
    expect(latest[0]).toHaveTextContent("RON 493.45 → €98.29");
    expect(latest[1]).toHaveTextContent("automatic");
    expect(within(latest[1]!).getByRole("link", { name: "pay_9" })).toHaveAttribute("href", "/payments/pay_9");
  });

  it("shows nothing when nothing was ever converted", async () => {
    vi.mocked(api.getFxReport).mockResolvedValue({ object: "fx_report", days: 30, data: [] });
    vi.mocked(api.listFxConversions).mockResolvedValue({ object: "list", data: [], has_more: false });
    const { container } = wrap(<FxReportCard />);
    // The card shows only once both answers are in and both are empty.
    await vi.waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});
