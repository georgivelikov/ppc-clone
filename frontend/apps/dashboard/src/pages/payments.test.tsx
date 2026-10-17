import type { Payment } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PaymentDetailPage, buildTimeline } from "./PaymentDetailPage";
import { PaymentsPage } from "./PaymentsPage";

vi.mock("../api", () => ({
  api: { listPayments: vi.fn(), getPayment: vi.fn(), createRefund: vi.fn(), cancelPayment: vi.fn() },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));

const { api } = await import("../api");

const payment: Payment = {
  id: "pay_1",
  object: "payment_intent",
  livemode: false,
  amount: 2500,
  currency: "EUR",
  status: "succeeded",
  capture_method: "automatic",
  description: "Flat white x2",
  customer_email: "anna@example.com",
  metadata: {},
  client_secret: "pay_1_secret_x",
  return_url: null,
  amount_refunded: 0,
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
    expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ starting_after: "pay_1" }));
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("filters by status", async () => {
    vi.mocked(api.listPayments).mockResolvedValue({ data: [], has_more: false });
    renderAt("/payments");
    await screen.findByText(/No payments yet/);

    await userEvent.selectOptions(screen.getByLabelText("Filter by status"), "failed");

    await waitFor(() => expect(api.listPayments).toHaveBeenLastCalledWith(expect.objectContaining({ status: "failed" })));
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

  it("shows card details without the full number", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(payment);
    renderAt("/payments/pay_1");

    expect(await screen.findByTestId("card-summary")).toHaveTextContent("visa •••• 4242");
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
});
