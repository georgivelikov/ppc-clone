import type { BalanceTransaction } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BalancesPage } from "./BalancesPage";

vi.mock("../api", () => ({
  api: { getBalance: vi.fn(), balanceSummary: vi.fn(), listBalanceTransactions: vi.fn() },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));
vi.mock("../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../api");

const payment: BalanceTransaction = {
  id: "txn_2",
  object: "balance_transaction",
  type: "payment",
  source_id: "pay_1",
  parent_id: null,
  currency: "EUR",
  gross: 10000,
  fee: 139,
  net: 9861,
  description: "Payment pay_1",
  available_on: "2026-10-19T10:00:00Z",
  created: "2026-10-17T10:00:00Z",
  livemode: false,
};
const refund: BalanceTransaction = {
  ...payment,
  id: "txn_1",
  type: "refund",
  source_id: "re_1",
  parent_id: "pay_1",
  gross: -3000,
  fee: 0,
  net: -3000,
  description: "Refund re_1 of pay_1",
  available_on: null,
};

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <BalancesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("BalancesPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.balanceSummary).mockResolvedValue({ object: "list", data: [] });
  });

  it("shows pending and available balances in the settlement currency", async () => {
    vi.mocked(api.getBalance).mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [{ amount: 9861, currency: "EUR" }],
      available: [{ amount: 2500, currency: "EUR" }],
      reserved: [],
    });
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderPage();

    expect(await screen.findByText("€98.61")).toBeInTheDocument();
    expect(screen.getByTestId("balance-pending")).toHaveTextContent("€98.61");
    expect(screen.getByTestId("balance-available")).toHaveTextContent("€25.00");
    expect(api.getBalance).toHaveBeenCalledWith(false);
  });

  it("shows a negative available balance as money owed", async () => {
    vi.mocked(api.getBalance).mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [],
      available: [{ amount: -3000, currency: "EUR" }],
      reserved: [],
    });
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderPage();

    // The card exists straight away with €0.00; wait for the loaded amount.
    const owed = await screen.findByText("-€30.00");
    expect(screen.getByTestId("balance-available")).toContainElement(owed);
    expect(owed).toHaveClass("text-danger");
  });

  it("lists transactions with gross, fee and net, and links them to payments", async () => {
    vi.mocked(api.getBalance).mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [],
      available: [],
      reserved: [],
    });
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({
      object: "list",
      data: [payment, refund],
      has_more: false,
    });
    renderPage();

    const paymentRow = (await screen.findByText("Payment pay_1")).closest("tr")!;
    expect(within(paymentRow).getByText("€100.00")).toBeInTheDocument();
    expect(within(paymentRow).getByText("-€1.39")).toBeInTheDocument();
    expect(within(paymentRow).getByText("€98.61")).toBeInTheDocument();
    expect(within(paymentRow).getByRole("link", { name: "Payment pay_1" })).toHaveAttribute("href", "/payments/pay_1");

    // A refund points at the payment it belongs to, not at itself.
    const refundRow = screen.getByText("Refund re_1 of pay_1").closest("tr")!;
    expect(within(refundRow).getByRole("link")).toHaveAttribute("href", "/payments/pay_1");
    // Gross and net are both -€30.00 (a refund has no fee), and both are shown as money out.
    const outflows = within(refundRow).getAllByText("-€30.00");
    expect(outflows).toHaveLength(2);
    outflows.forEach((cell) => expect(cell).toHaveClass("text-danger"));
  });

  it("loads the next page with the cursor", async () => {
    vi.mocked(api.getBalance).mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [],
      available: [],
      reserved: [],
    });
    vi.mocked(api.listBalanceTransactions)
      .mockResolvedValueOnce({ object: "list", data: [payment], has_more: true })
      .mockResolvedValueOnce({
        object: "list",
        data: [{ ...payment, id: "txn_0", source_id: "pay_0", description: "Payment pay_0" }],
        has_more: false,
      });
    renderPage();

    await screen.findByText("Payment pay_1");
    await userEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("Payment pay_0")).toBeInTheDocument();
    expect(api.listBalanceTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ starting_after: "txn_2" }));
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("explains an empty ledger", async () => {
    vi.mocked(api.getBalance).mockResolvedValue({
      object: "balance",
      livemode: false,
      pending: [],
      available: [],
      reserved: [],
    });
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderPage();

    expect(await screen.findByText(/No transactions yet/)).toBeInTheDocument();
    expect(await screen.findByText("No card payments in this period.")).toBeInTheDocument();
  });
});
