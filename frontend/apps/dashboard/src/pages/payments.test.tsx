import type { Payment } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PaymentDetailPage, buildTimeline } from "./PaymentDetailPage";
import { PaymentsPage } from "./PaymentsPage";

vi.mock("../api", () => ({
  api: {
    listPayments: vi.fn(),
    getPayment: vi.fn(),
    createRefund: vi.fn(),
    cancelPayment: vi.fn(),
    capturePayment: vi.fn(),
    listBalanceTransactions: vi.fn(),
  },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));

let testMode = true;
vi.mock("../layout/testMode", () => ({ useTestMode: () => ({ testMode, setTestMode: vi.fn() }) }));

const { api } = await import("../api");

const payment: Payment = {
  id: "pay_1",
  object: "payment_intent",
  livemode: false,
  amount: 2500,
  currency: "EUR",
  status: "succeeded",
  capture_method: "automatic",
  channel: "online",
  description: "Flat white x2",
  customer_email: "anna@example.com",
  metadata: {},
  client_secret: "pay_1_secret_x",
  return_url: null,
  amount_refunded: 0,
  amount_authorized: null,
  capture_before: null,
  customer: null,
  payment_method: null,
  off_session: false,
  failure_code: null,
  failure_message: null,
  created_at: "2026-10-17T10:00:00Z",
  updated_at: "2026-10-17T10:00:05Z",
  attempts: [
    {
      id: "att_1",
      method_type: "card",
      card_brand: "visa",
      card_last4: "4242",
      card_exp_month: 12,
      card_exp_year: 2099,
      status: "succeeded",
      decline_code: null,
      three_ds_status: null,
      created_at: "2026-10-17T10:00:02Z",
    },
  ],
  refunds: [],
};

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/payments" element={<PaymentsPage />} />
          <Route path="/payments/:id" element={<PaymentDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("payments pages", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("lists payments and loads the next page with the cursor", async () => {
    vi.mocked(api.listPayments)
      .mockResolvedValueOnce({ data: [payment], has_more: true })
      .mockResolvedValueOnce({ data: [{ ...payment, id: "pay_0", amount: 1000 }], has_more: false });
    renderAt("/payments");

    expect(await screen.findByText("€25.00")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("€10.00")).toBeInTheDocument();
    expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ starting_after: "pay_1", livemode: false }));
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("asks for live payments when test mode is off", async () => {
    testMode = false;
    try {
      vi.mocked(api.listPayments).mockResolvedValue({ data: [], has_more: false });
      renderAt("/payments");
      await screen.findByText(/No payments yet/);
      expect(api.listPayments).toHaveBeenCalledWith(expect.objectContaining({ livemode: true }));
    } finally {
      testMode = true;
    }
  });

  it("filters by status", async () => {
    vi.mocked(api.listPayments).mockResolvedValue({ data: [], has_more: false });
    renderAt("/payments");
    await screen.findByText(/No payments yet/);

    await userEvent.selectOptions(screen.getByLabelText("Filter by status"), "failed");

    await waitFor(() => expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ status: "failed" })));
  });

  it("shows only held payments with the Uncaptured toggle, and the status filter follows it", async () => {
    vi.mocked(api.listPayments).mockResolvedValue({ data: [], has_more: false });
    renderAt("/payments");
    await screen.findByText(/No payments yet/);

    const toggle = screen.getByRole("button", { name: "Uncaptured only" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(toggle);

    await waitFor(() => expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ status: "requires_capture" })));
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("Filter by status")).toHaveValue("requires_capture");

    await userEvent.click(toggle);
    await waitFor(() => expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ status: "" })));
    expect(toggle).toHaveAttribute("aria-pressed", "false");
  });

  it("refunds part of a payment and rejects more than what is left", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment);
    vi.mocked(api.createRefund).mockResolvedValue({
      id: "re_1",
      payment_intent: "pay_1",
      amount: 1000,
      currency: "EUR",
      reason: null,
      status: "succeeded",
      failure_code: null,
      created_at: "2026-10-17T10:05:00Z",
    });
    renderAt("/payments/pay_1");

    await userEvent.click(await screen.findByRole("button", { name: "Refund" }));
    const dialog = await screen.findByRole("dialog");
    const amount = screen.getByLabelText(/Refund amount/);
    expect(amount).toHaveValue("25.00");

    await userEvent.clear(amount);
    await userEvent.type(amount, "30");
    await userEvent.click(within(dialog).getByRole("button", { name: "Refund" }));
    expect(await screen.findByText("You can refund at most €25.00")).toBeInTheDocument();
    expect(api.createRefund).not.toHaveBeenCalled();

    await userEvent.clear(amount);
    await userEvent.type(amount, "10");
    await userEvent.click(within(dialog).getByRole("button", { name: "Refund" }));
    await waitFor(() =>
      expect(api.createRefund).toHaveBeenCalledWith({ payment_intent: "pay_1", amount: 1000, reason: undefined }),
    );
  });

  it("captures part of a held payment, says how much is released and refuses more than was held", async () => {
    const held: Payment = {
      ...payment,
      amount: 20000,
      status: "requires_capture",
      capture_method: "manual",
      amount_authorized: 20000,
      capture_before: "2026-10-25T10:00:00Z",
    };
    vi.mocked(api.getPayment).mockResolvedValue(held);
    vi.mocked(api.capturePayment).mockResolvedValue({ ...held, status: "succeeded", amount: 15000 });
    renderAt("/payments/pay_1");

    expect(await screen.findByText("Uncaptured")).toBeInTheDocument();
    expect(screen.getByTestId("payment-authorized")).toHaveTextContent("€200.00");
    expect(screen.getByRole("button", { name: "Release hold" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Capture" }));
    const dialog = await screen.findByRole("dialog");
    const amount = screen.getByLabelText(/Amount to capture/);
    expect(amount).toHaveValue("200.00");

    await userEvent.clear(amount);
    await userEvent.type(amount, "250");
    await userEvent.click(within(dialog).getByRole("button", { name: "Capture" }));
    expect(await screen.findByText("You can capture at most €200.00")).toBeInTheDocument();
    expect(api.capturePayment).not.toHaveBeenCalled();

    await userEvent.clear(amount);
    await userEvent.type(amount, "150");
    expect(screen.getByTestId("capture-release-note")).toHaveTextContent("€50.00 will be released");
    await userEvent.click(within(dialog).getByRole("button", { name: "Capture" }));
    await waitFor(() => expect(api.capturePayment).toHaveBeenCalledWith("pay_1", 15000));
  });

  it("explains in the timeline when an unclaimed hold was released", () => {
    const events = buildTimeline({ ...payment, status: "canceled", failure_code: "authorization_expired" });
    expect(events.at(-1)?.title).toBe("Hold released: not captured in time");
  });

  it("shows card details without the full number", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment);
    renderAt("/payments/pay_1");

    expect(await screen.findByTestId("card-summary")).toHaveTextContent("visa •••• 4242");
  });

  it("names the wallet that presented the card and says the wallet did the authentication", async () => {
    const attempt = { ...payment.attempts[0]!, method_type: "google_pay" };
    vi.mocked(api.getPayment).mockResolvedValue({ ...payment, payment_method_type: "google_pay", attempts: [attempt] });
    renderAt("/payments/pay_1");

    expect(await screen.findByTestId("method-wallet")).toHaveTextContent("Google Pay");
    expect(screen.getByTestId("card-summary")).toHaveTextContent("visa •••• 4242");
    expect(screen.getByText("Done by the wallet")).toBeInTheDocument();
    expect(buildTimeline({ ...payment, attempts: [attempt] }).map((event) => event.title)).toContain(
      "Payment attempted with Google Pay (visa •••• 4242)",
    );
  });

  it("shows a BLIK payment without any card", async () => {
    const attempt = { ...payment.attempts[0]!, method_type: "blik", card_brand: null, card_last4: null };
    vi.mocked(api.getPayment).mockResolvedValue({ ...payment, currency: "PLN", payment_method_type: "blik", attempts: [attempt] });
    renderAt("/payments/pay_1");

    expect(await screen.findByTestId("method-blik")).toHaveTextContent("Paid with BLIK");
    expect(screen.queryByTestId("card-summary")).not.toBeInTheDocument();
  });

  it("builds a timeline with attempts, the outcome and refunds in order", () => {
    const events = buildTimeline({
      ...payment,
      status: "partially_refunded",
      refunds: [
        {
          id: "re_1",
          payment_intent: "pay_1",
          amount: 500,
          currency: "EUR",
          reason: "Spilled",
          status: "succeeded",
          failure_code: null,
          created_at: "2026-10-17T11:00:00Z",
        },
      ],
    });

    expect(events.map((e) => e.title)).toEqual([
      "Payment created",
      "Payment attempted with visa •••• 4242",
      "Payment succeeded",
      "Refund of €5.00 succeeded",
    ]);
  });

  it("shows the fee and what the merchant receives once the ledger has priced the payment", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment);
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({
      object: "list",
      has_more: false,
      data: [
        {
          id: "txn_1",
          object: "balance_transaction",
          type: "payment",
          source_id: "pay_1",
          parent_id: null,
          currency: "EUR",
          gross: 2500,
          fee: 42,
          net: 2458,
          description: "Payment pay_1",
          available_on: "2026-10-19T10:00:00Z",
          created: "2026-10-17T10:00:00Z",
          livemode: false,
        },
      ],
    });
    renderAt("/payments/pay_1");

    // The rows show "Calculating…" until the ledger answers.
    expect(await screen.findByText("−€0.42")).toBeInTheDocument();
    expect(screen.getByTestId("payment-fee")).toHaveTextContent("−€0.42");
    expect(screen.getByTestId("payment-net")).toHaveTextContent("€24.58");
    expect(api.listBalanceTransactions).toHaveBeenCalledWith(expect.objectContaining({ source: "pay_1" }));
  });

  it("does not ask the ledger about a payment that has no fee yet", async () => {
    vi.mocked(api.getPayment).mockResolvedValue({ ...payment, status: "requires_payment_method", attempts: [] });
    renderAt("/payments/pay_1");

    await screen.findByTestId("payment-amount");
    expect(screen.queryByTestId("payment-fee")).not.toBeInTheDocument();
    expect(api.listBalanceTransactions).not.toHaveBeenCalled();
  });
});
