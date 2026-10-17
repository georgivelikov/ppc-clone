import type { Payout, PayoutPreview } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PayoutDetailPage } from "./PayoutDetailPage";
import { PayoutsPage } from "./PayoutsPage";

vi.mock("../api", () => ({
  api: {
    payoutPreview: vi.fn(),
    listPayouts: vi.fn(),
    createPayout: vi.fn(),
    getPayout: vi.fn(),
    payoutReconciliation: vi.fn(),
  },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));
vi.mock("../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../api");

const payout: Payout = {
  id: "po_1",
  object: "payout",
  amount: 9861,
  fee: 0,
  currency: "EUR",
  status: "paid",
  kind: "scheduled",
  arrival_date: "2026-10-19",
  bank_account_id: "ba_1",
  failure_code: null,
  created: "2026-10-18T10:00:00Z",
  livemode: false,
};

const preview = (overrides: Partial<PayoutPreview> = {}): PayoutPreview => ({
  currency: "EUR",
  available: 10000,
  fee: 100,
  net: 9900,
  has_bank_account: true,
  ...overrides,
});

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/payouts" element={<PayoutsPage />} />
          <Route path="/payouts/:id" element={<PayoutDetailPage />} />
          <Route path="/settings/payouts" element={<p>payout settings</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PayoutsPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.listPayouts).mockResolvedValue({ object: "list", data: [], has_more: false });
  });

  it("lists payouts with their status", async () => {
    vi.mocked(api.payoutPreview).mockResolvedValue(preview({ available: 0, fee: 0, net: 0 }));
    vi.mocked(api.listPayouts).mockResolvedValue({
      object: "list",
      data: [payout, { ...payout, id: "po_0", amount: 5000, status: "failed", failure_code: "account_closed" }],
      has_more: false,
    });
    renderAt("/payouts");

    const row = (await screen.findByText("€98.61")).closest("tr")!;
    expect(within(row).getByText("Paid")).toBeInTheDocument();
    expect(within(row).getByRole("link")).toHaveAttribute("href", "/payouts/po_1");
    expect(screen.getByText("Failed")).toBeInTheDocument();
  });

  it("asks for a bank account before anything can be paid out", async () => {
    vi.mocked(api.payoutPreview).mockResolvedValue(preview({ has_bank_account: false }));
    renderAt("/payouts");

    expect(await screen.findByText(/Add a bank account to receive payouts/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay out now" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Go to payout settings" })).toHaveAttribute("href", "/settings/payouts");
  });

  it("disables paying out when nothing is available", async () => {
    vi.mocked(api.payoutPreview).mockResolvedValue(preview({ available: 0, fee: 0, net: 0 }));
    renderAt("/payouts");

    expect(await screen.findByTestId("payable-amount")).toHaveTextContent("€0.00");
    expect(screen.getByRole("button", { name: "Pay out now" })).toBeDisabled();
  });

  it("shows the instant payout fee, then pays out after confirmation", async () => {
    vi.mocked(api.payoutPreview).mockResolvedValue(preview());
    vi.mocked(api.createPayout).mockResolvedValue({ ...payout, id: "po_9", amount: 9900, fee: 100, kind: "instant" });
    vi.mocked(api.getPayout).mockResolvedValue({ payout: { ...payout, id: "po_9", amount: 9900 }, items: [] });
    vi.mocked(api.payoutReconciliation).mockResolvedValue({
      payout_id: "po_9",
      amount: 9900,
      fee: 100,
      items_total: 10000,
      item_count: 1,
      balanced: true,
    });
    renderAt("/payouts");

    await userEvent.click(await screen.findByRole("button", { name: "Pay out now" }));
    const dialog = screen.getByRole("dialog", { name: "Pay out now" });
    expect(within(dialog).getByText("−€1.00")).toBeInTheDocument();
    expect(within(dialog).getByTestId("payout-net")).toHaveTextContent("€99.00");
    expect(api.createPayout).not.toHaveBeenCalled();

    await userEvent.click(within(dialog).getByRole("button", { name: "Confirm payout" }));

    expect(api.createPayout).toHaveBeenCalledWith("EUR", false);
    expect(await screen.findByTestId("payout-amount")).toHaveTextContent("€99.00");
  });
});

describe("PayoutDetailPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("shows the transactions in a payout and that they reconcile", async () => {
    vi.mocked(api.getPayout).mockResolvedValue({
      payout,
      items: [
        { journal_entry_ref: "je_1", source_id: "pay_1", amount: 12861 },
        { journal_entry_ref: "je_2", source_id: "re_1", amount: -3000 },
      ],
    });
    vi.mocked(api.payoutReconciliation).mockResolvedValue({
      payout_id: "po_1",
      amount: 9861,
      fee: 0,
      items_total: 9861,
      item_count: 2,
      balanced: true,
    });
    renderAt("/payouts/po_1");

    expect(await screen.findByTestId("payout-amount")).toHaveTextContent("€98.61");
    expect(screen.getByRole("link", { name: "pay_1" })).toHaveAttribute("href", "/payments/pay_1");
    expect(await screen.findByTestId("reconciliation")).toHaveTextContent(/Reconciled: 2 transactions add up to €98.61/);
  });

  it("flags a payout that does not reconcile", async () => {
    vi.mocked(api.getPayout).mockResolvedValue({ payout, items: [] });
    vi.mocked(api.payoutReconciliation).mockResolvedValue({
      payout_id: "po_1",
      amount: 9861,
      fee: 0,
      items_total: 9000,
      item_count: 1,
      balanced: false,
    });
    renderAt("/payouts/po_1");

    expect(await screen.findByTestId("reconciliation")).toHaveTextContent(/Does not reconcile/);
  });

  it("explains a payout the bank rejected", async () => {
    vi.mocked(api.getPayout).mockResolvedValue({
      payout: { ...payout, status: "failed", failure_code: "account_closed" },
      items: [],
    });
    vi.mocked(api.payoutReconciliation).mockResolvedValue({
      payout_id: "po_1",
      amount: 9861,
      fee: 0,
      items_total: 9861,
      item_count: 1,
      balanced: true,
    });
    renderAt("/payouts/po_1");

    expect(await screen.findByText(/The bank rejected this payout/)).toHaveTextContent(/The bank account is closed/);
  });
});
