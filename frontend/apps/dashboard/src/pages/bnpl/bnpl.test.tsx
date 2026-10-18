import { ApiError, type BnplAnalytics, type BnplApplication, type BnplSettings, type Payment, type RoutingDecision } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { durationText, percentText, planLabel } from "../../lib/bnpl";
import { PaymentDetailPage } from "../PaymentDetailPage";
import { BnplPage } from "./BnplPage";

vi.mock("../../api", () => ({
  api: {
    getBnplSettings: vi.fn(),
    updateBnplSettings: vi.fn(),
    getBnplAnalytics: vi.fn(),
    listBnplApplications: vi.fn(),
    getRoutingDecision: vi.fn(),
    getPayment: vi.fn(),
    listBalanceTransactions: vi.fn(),
  },
}));
vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const provider = (code: string, name: string, priority: number, enabled = true, min = 5000, max = 150000) => ({
  code,
  name,
  enabled,
  priority,
  min_amount: min,
  max_amount: max,
  currencies: ["EUR"],
  countries: ["BG", "DE"],
});

const settings = (over: Partial<BnplSettings> = {}): BnplSettings => ({
  object: "bnpl_settings",
  routing_mode: "customer_choice",
  weights: { cost: 1, speed: 1, approval: 1 },
  providers: [
    provider("splitnow", "SplitNow", 0),
    provider("lendo", "Lendo", 1, true, 10000, 1000000),
    provider("kreditko", "KreditKo", 2, true, 20000, 1500000),
    provider("paylatereu", "PayLaterEU", 3, true, 2000, 80000),
  ],
  ...over,
});

const analytics = (over: Partial<BnplAnalytics> = {}): BnplAnalytics => ({
  object: "bnpl_analytics",
  days: 30,
  applications: 12,
  approved: 7,
  volume: 210000,
  fees: 7350,
  providers: [
    { provider: "splitnow", name: "SplitNow", applications: 8, approved: 6, declined: 2, failed: 0, pending: 0, abandoned: 0,
      approval_rate: 0.75, avg_decision_ms: 320, p50_decision_ms: 300, volume: 180000, fees: 6300, circuit: "closed" },
    { provider: "lendo", name: "Lendo", applications: 4, approved: 1, declined: 1, failed: 0, pending: 2, abandoned: 0,
      approval_rate: 0.5, avg_decision_ms: 9000, p50_decision_ms: 12000, volume: 30000, fees: 870, circuit: "closed" },
    { provider: "kreditko", name: "KreditKo", applications: 0, approved: 0, declined: 0, failed: 0, pending: 0, abandoned: 0,
      approval_rate: null, avg_decision_ms: 0, p50_decision_ms: 0, volume: 0, fees: 0, circuit: "half_open" },
    { provider: "paylatereu", name: "PayLaterEU", applications: 3, approved: 0, declined: 0, failed: 3, pending: 0, abandoned: 0,
      approval_rate: null, avg_decision_ms: 0, p50_decision_ms: 0, volume: 0, fees: 0, circuit: "open" },
  ],
  ...over,
});

const application = (over: Partial<BnplApplication> = {}): BnplApplication => ({
  id: "bnpl_1",
  object: "bnpl_application",
  livemode: false,
  payment: "pay_1",
  provider: "splitnow",
  provider_name: "SplitNow",
  plan: "splitnow_3",
  status: "approved",
  provider_ref: "sn_1",
  amount: 30000,
  currency: "EUR",
  country: "BG",
  instalments: 3,
  instalment_amounts: [10000, 10000, 10000],
  interval_days: 14,
  apr: 0,
  total_payable: 30000,
  merchant_fee: 1050,
  net_amount: 28950,
  customer_email: "anna@example.com",
  decline_reason: null,
  decision_ms: 310,
  expires_at: "2026-10-20T10:00:00Z",
  created_at: "2026-10-19T10:00:00Z",
  decided_at: "2026-10-19T10:00:01Z",
  ...over,
});

function renderPage(path: string, element: React.ReactNode, route: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={route} element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("the wording on the instalments page", () => {
  it("shows durations, rates and plans the way people read them", () => {
    expect(durationText(0)).toBe("—");
    expect(durationText(300)).toBe("300 ms");
    expect(durationText(12000)).toBe("12 s");
    expect(durationText(1500)).toBe("1.5 s");
    expect(durationText(90000)).toBe("1.5 min");
    expect(percentText(null)).toBe("—");
    expect(percentText(0.857)).toBe("86%");
    expect(planLabel(application())).toBe("3 × €100.00");
    expect(planLabel(application({ instalments: 1, instalment_amounts: [30000], interval_days: 30 }))).toBe("€300.00 in 30 days");
  });
});

describe("the instalments page", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.getBnplSettings).mockResolvedValue(settings());
    vi.mocked(api.getBnplAnalytics).mockResolvedValue(analytics());
    vi.mocked(api.listBnplApplications).mockResolvedValue({ object: "list", data: [application()], has_more: false });
  });

  const open = () => renderPage("/instalments", <BnplPage />, "/instalments");

  it("lists the providers in the merchant's order with what each can finance", async () => {
    open();

    const rows = await screen.findAllByTestId(/^provider-/);
    expect(rows.map((row) => row.getAttribute("data-testid"))).toEqual([
      "provider-splitnow",
      "provider-lendo",
      "provider-kreditko",
      "provider-paylatereu",
    ]);
    expect(within(rows[0]!).getByText("€50.00 to €1,500.00 · BG, DE")).toBeInTheDocument();
    expect(screen.getByLabelText("How the provider is chosen")).toHaveValue("customer_choice");
    expect(screen.queryByRole("group", { name: "Weights" })).not.toBeInTheDocument();
  });

  it("switches a provider off at once", async () => {
    vi.mocked(api.updateBnplSettings).mockResolvedValue(settings());
    open();

    await userEvent.click(await screen.findByRole("checkbox", { name: "Offer Lendo" }));

    await waitFor(() => expect(api.updateBnplSettings).toHaveBeenCalledWith({ providers: [{ code: "lendo", enabled: false }] }, false));
  });

  it("moves a provider up or down one place and cannot move the ends past themselves", async () => {
    vi.mocked(api.updateBnplSettings).mockResolvedValue(settings());
    open();
    await screen.findByTestId("provider-lendo");

    expect(screen.getByRole("button", { name: "Move SplitNow up" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Move PayLaterEU down" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Move KreditKo up" }));
    await waitFor(() => expect(api.updateBnplSettings).toHaveBeenLastCalledWith({ providers: [{ code: "kreditko", priority: 1 }] }, false));
    await userEvent.click(screen.getByRole("button", { name: "Move SplitNow down" }));
    await waitFor(() => expect(api.updateBnplSettings).toHaveBeenLastCalledWith({ providers: [{ code: "splitnow", priority: 1 }] }, false));
  });

  it("changes how the provider is chosen and asks for weights only in the weighted mode", async () => {
    vi.mocked(api.updateBnplSettings).mockResolvedValue(settings({ routing_mode: "weighted", weights: { cost: 3, speed: 1, approval: 2 } }));
    open();

    await userEvent.selectOptions(await screen.findByLabelText("How the provider is chosen"), "weighted");
    await waitFor(() => expect(api.updateBnplSettings).toHaveBeenCalledWith({ routing_mode: "weighted" }, false));

    const weights = await screen.findByRole("group", { name: "Weights" });
    expect(within(weights).getByLabelText("Cost")).toHaveValue(3);
    await userEvent.clear(within(weights).getByLabelText("Cost"));
    await userEvent.type(within(weights).getByLabelText("Cost"), "5");
    await userEvent.click(within(weights).getByRole("button", { name: "Save weights" }));
    await waitFor(() => expect(api.updateBnplSettings).toHaveBeenLastCalledWith({ weights: { cost: 5, speed: 1, approval: 2 } }, false));
  });

  it("refuses weights that make no sense before sending them", async () => {
    vi.mocked(api.getBnplSettings).mockResolvedValue(settings({ routing_mode: "weighted" }));
    open();
    const weights = await screen.findByRole("group", { name: "Weights" });
    for (const label of ["Cost", "Speed", "Approval rate"]) {
      await userEvent.clear(within(weights).getByLabelText(label));
      await userEvent.type(within(weights).getByLabelText(label), "0");
    }

    await userEvent.click(within(weights).getByRole("button", { name: "Save weights" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("at least one must be above 0");
    expect(api.updateBnplSettings).not.toHaveBeenCalled();
  });

  it("shows the server's reason when a change is refused", async () => {
    vi.mocked(api.updateBnplSettings).mockRejectedValue(new ApiError(400, "invalid_request_error", "unknown_provider", "No such provider: x"));
    open();

    await userEvent.click(await screen.findByRole("checkbox", { name: "Offer SplitNow" }));

    expect(await screen.findByText("No such provider: x")).toBeInTheDocument();
  });

  it("shows how each provider has done and whether it is working now", async () => {
    open();

    expect(await screen.findByTestId("bnpl-totals")).toHaveTextContent("12 applications · €2,100.00 financed · €73.50 in fees");
    const splitnow = within(await screen.findByTestId("analytics-splitnow"));
    expect(splitnow.getByText("75%")).toBeInTheDocument();
    expect(splitnow.getByText("300 ms")).toBeInTheDocument();
    expect(splitnow.getByText("2 declined")).toBeInTheDocument();
    expect(splitnow.getByText("Working")).toBeInTheDocument();
    expect(within(screen.getByTestId("analytics-lendo")).getByText("12 s")).toBeInTheDocument();
    expect(within(screen.getByTestId("analytics-kreditko")).getByText("Checking")).toBeInTheDocument();
    const payLater = within(screen.getByTestId("analytics-paylatereu"));
    expect(payLater.getByText("Down")).toBeInTheDocument();
    expect(payLater.getByText("3 not reached")).toBeInTheDocument();
  });

  it("looks at another period", async () => {
    open();
    await screen.findByTestId("bnpl-totals");

    await userEvent.selectOptions(screen.getByLabelText("Period"), "90");

    await waitFor(() => expect(api.getBnplAnalytics).toHaveBeenLastCalledWith(90, false));
  });

  it("lists applications with the fee and links them to their payment, and filters by status", async () => {
    open();

    const row = within(await screen.findByTestId("bnpl-application"));
    expect(row.getByText("SplitNow")).toBeInTheDocument();
    expect(row.getByText("3 × €100.00")).toBeInTheDocument();
    expect(row.getByText("€300.00")).toBeInTheDocument();
    expect(row.getByText("€10.50")).toBeInTheDocument();
    expect(row.getByText("Approved")).toBeInTheDocument();
    expect(row.getByText("310 ms")).toBeInTheDocument();
    expect(row.getByRole("link")).toHaveAttribute("href", "/payments/pay_1");

    await userEvent.selectOptions(screen.getByLabelText("Filter by status"), "declined");
    await waitFor(() => expect(api.listBnplApplications).toHaveBeenLastCalledWith(expect.objectContaining({ status: "declined" })));
  });

  it("says so when there is nothing yet", async () => {
    vi.mocked(api.listBnplApplications).mockResolvedValue({ object: "list", data: [], has_more: false });
    open();

    expect(await screen.findByText("No applications yet")).toBeInTheDocument();
  });
});

describe("a payment financed in instalments", () => {
  const payment = (over: Partial<Payment> = {}): Payment => ({
    id: "pay_1",
    object: "payment_intent",
    livemode: false,
    amount: 30000,
    currency: "EUR",
    status: "succeeded",
    capture_method: "automatic",
    channel: "online",
    description: "Sofa",
    customer_email: "anna@example.com",
    metadata: {},
    client_secret: "s",
    return_url: null,
    amount_refunded: 0,
    amount_authorized: null,
    capture_before: null,
    customer: null,
    payment_method: null,
    off_session: false,
    failure_code: null,
    failure_message: null,
    created_at: "2026-10-19T10:00:00Z",
    updated_at: "2026-10-19T10:00:05Z",
    payment_method_type: "bnpl",
    bnpl: { provider: "splitnow", application: "bnpl_1", fee: 1050 },
    attempts: [
      { id: "att_1", method_type: "bnpl", card_brand: null, card_last4: null, card_exp_month: null, card_exp_year: null, status: "succeeded",
        decline_code: null, three_ds_status: null, created_at: "2026-10-19T10:00:04Z" },
    ],
    refunds: [],
    ...over,
  });

  const decision: RoutingDecision = {
    object: "routing_decision",
    id: "rd_1",
    payment: "pay_1",
    strategy: "cheapest",
    amount: 30000,
    currency: "EUR",
    country: "BG",
    chosen_provider: "paylatereu",
    reason: "Chose PayLaterEU: cheapest eligible (2.5% vs 2.9% for Lendo); KreditKo excluded: not available in NL",
    ranked: [
      { provider: "paylatereu", name: "PayLaterEU", rank: 1, score: 1, best: { plan: "paylatereu_30", merchant_fee: 750, total_payable: 30000 },
        stats: { approval_rate: 0.8, p50_decision_ms: 600 } },
      { provider: "lendo", name: "Lendo", rank: 2, score: 0.5, best: { plan: "lendo_6", merchant_fee: 870, total_payable: 30654 },
        stats: { approval_rate: 0.7, p50_decision_ms: 12000 } },
    ],
    excluded: [{ provider: "kreditko", code: "country", message: "not available in NL" }],
    created_at: "2026-10-19T09:59:00Z",
  };

  const open = () => renderPage("/payments/pay_1", <PaymentDetailPage />, "/payments/:id");

  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.getRoutingDecision).mockRejectedValue(new ApiError(404, "invalid_request_error", "routing_decision_not_found", "none"));
  });

  it("shows who financed it, what they kept and what the merchant receives, and no card", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment());
    open();

    const card = within(await screen.findByLabelText("Instalments"));
    expect(card.getByText("Paid in instalments")).toBeInTheDocument();
    expect(card.getByText("splitnow")).toBeInTheDocument();
    expect(card.getByTestId("bnpl-fee")).toHaveTextContent("−€10.50");
    expect(card.getByTestId("bnpl-net")).toHaveTextContent("€289.50");
    expect(screen.getByTestId("method-instalments")).toHaveTextContent("Paid in instalments with splitnow.");
    expect(screen.queryByTestId("card-summary")).not.toBeInTheDocument();
    expect(screen.getByText("Approved for instalments")).toBeInTheDocument();
  });

  it("explains why the providers were offered the way they were", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment());
    vi.mocked(api.getRoutingDecision).mockResolvedValue(decision);
    open();

    expect(await screen.findByTestId("bnpl-reason")).toHaveTextContent(
      "Chose PayLaterEU: cheapest eligible (2.5% vs 2.9% for Lendo); KreditKo excluded: not available in NL",
    );
    const explainer = within(screen.getByTestId("bnpl-decision"));
    expect(explainer.getByText("PayLaterEU")).toBeInTheDocument();
    expect(explainer.getByText("€7.50")).toBeInTheDocument();
    expect(explainer.getByText("80%")).toBeInTheDocument();
    expect(explainer.getByText("12 s")).toBeInTheDocument();
    expect(explainer.getByText(/left out: not available in NL/)).toBeInTheDocument();
    expect(api.getRoutingDecision).toHaveBeenCalledWith("pay_1", false);
  });

  it("explains the offers even when the customer paid by card", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment({ payment_method_type: "card", bnpl: null, attempts: [] }));
    vi.mocked(api.getRoutingDecision).mockResolvedValue(decision);
    open();

    const card = within(await screen.findByLabelText("Instalments"));
    expect(card.getByText("Instalments were offered")).toBeInTheDocument();
    expect(card.queryByTestId("bnpl-financed")).not.toBeInTheDocument();
  });

  it("shows nothing about instalments for an ordinary card payment", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment({ payment_method_type: "card", bnpl: null, attempts: [] }));
    open();

    await screen.findByTestId("payment-amount");
    await waitFor(() => expect(api.getRoutingDecision).toHaveBeenCalled());
    expect(screen.queryByLabelText("Instalments")).not.toBeInTheDocument();
  });
});
