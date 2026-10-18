import type { ConnectedAccount } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CreatePaymentDialog } from "../CreatePaymentDialog";
import { ConnectedAccountDetailPage } from "./ConnectedAccountDetailPage";
import { ConnectedAccountsPage } from "./ConnectedAccountsPage";

vi.mock("../../api", () => ({
  api: {
    listConnectedAccounts: vi.fn(),
    createConnectedAccount: vi.fn(),
    getConnectedAccount: vi.fn(),
    updateConnectedAccount: vi.fn(),
    disableConnectedAccount: vi.fn(),
    getBalance: vi.fn(),
    listBalanceTransactions: vi.fn(),
    createCheckoutSession: vi.fn(),
  },
}));
vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Delivery Hero", default_currency: "EUR" } }),
}));
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const pizza: ConnectedAccount = {
  id: "acct_pizza",
  object: "connected_account",
  livemode: false,
  name: "Pizza Palace",
  email: "owner@pizza.example",
  country: "BG",
  currency: "EUR",
  iban_last4: "5678",
  status: "active",
  created_at: "2026-10-18T10:00:00Z",
};

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/connected-accounts" element={<ConnectedAccountsPage />} />
          <Route path="/connected-accounts/:id" element={<ConnectedAccountDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const balance = (pending: number, available: number) => ({
  object: "balance" as const,
  livemode: false,
  pending: [{ amount: pending, currency: "EUR" }],
  available: [{ amount: available, currency: "EUR" }],
  reserved: [],
});

describe("connected accounts", () => {
  beforeEach(() => vi.resetAllMocks());

  it("lists the accounts with what each is owed", async () => {
    vi.mocked(api.listConnectedAccounts).mockResolvedValue({ object: "list", data: [pizza], has_more: false });
    vi.mocked(api.getBalance).mockResolvedValue(balance(6000, 1500));
    renderAt("/connected-accounts");

    expect(await screen.findByRole("link", { name: "Pizza Palace" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("balance-acct_pizza")).toHaveTextContent("€60.00 pending · €15.00 available"));
    expect(api.getBalance).toHaveBeenCalledWith(false, "acct_pizza");
  });

  it("checks the form and creates an account", async () => {
    vi.mocked(api.listConnectedAccounts).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.createConnectedAccount).mockResolvedValue(pizza);
    vi.mocked(api.getConnectedAccount).mockResolvedValue(pizza);
    vi.mocked(api.getBalance).mockResolvedValue(balance(0, 0));
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderAt("/connected-accounts");

    await userEvent.click(await screen.findByRole("button", { name: "New account" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Create account" }));
    expect(await within(dialog).findByText("Give the account a name")).toBeInTheDocument();
    expect(within(dialog).getByText("Enter the IBAN it is paid out to")).toBeInTheDocument();
    expect(api.createConnectedAccount).not.toHaveBeenCalled();

    await userEvent.type(within(dialog).getByLabelText("Name"), "Pizza Palace");
    await userEvent.type(within(dialog).getByLabelText("IBAN"), "BG80 BNBG 9661 1020 3456 78");
    await userEvent.click(within(dialog).getByRole("button", { name: "Create account" }));
    await waitFor(() =>
      expect(api.createConnectedAccount).toHaveBeenCalledWith({ name: "Pizza Palace", email: undefined, iban: "BG80 BNBG 9661 1020 3456 78" }, false),
    );
    expect(await screen.findByTestId("account-name")).toHaveTextContent("Pizza Palace");
  });

  it("shows the balance and the transfers and refunds taken back on the account page", async () => {
    vi.mocked(api.getConnectedAccount).mockResolvedValue(pizza);
    vi.mocked(api.getBalance).mockResolvedValue(balance(3000, 0));
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({
      object: "list",
      has_more: false,
      data: [
        { id: "txn_2", object: "balance_transaction", type: "transfer_reversal", source_id: "re_1", parent_id: "pay_1", currency: "EUR", gross: -3000, fee: 0, net: -3000, description: "x", available_on: null, created: "2026-10-18T11:00:00Z", livemode: false },
        { id: "txn_1", object: "balance_transaction", type: "transfer", source_id: "pay_1", parent_id: null, currency: "EUR", gross: 6000, fee: 0, net: 6000, description: "x", available_on: null, created: "2026-10-18T10:00:00Z", livemode: false },
      ],
    });
    renderAt("/connected-accounts/acct_pizza");

    await waitFor(() => expect(screen.getByTestId("account-pending")).toHaveTextContent("€30.00"));
    const rows = within(screen.getByTestId("account-activity")).getAllByRole("row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Refund taken back");
    expect(rows[0]).toHaveTextContent("-€30.00");
    expect(rows[1]).toHaveTextContent("Transfer");
    expect(rows[1]).toHaveTextContent("€60.00");
  });

  it("disables an account", async () => {
    vi.mocked(api.getConnectedAccount).mockResolvedValue(pizza);
    vi.mocked(api.getBalance).mockResolvedValue(balance(0, 0));
    vi.mocked(api.listBalanceTransactions).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.disableConnectedAccount).mockResolvedValue({ ...pizza, status: "disabled" });
    renderAt("/connected-accounts/acct_pizza");

    await userEvent.click(await screen.findByRole("button", { name: "Disable" }));
    await waitFor(() => expect(api.disableConnectedAccount).toHaveBeenCalledWith("acct_pizza"));
  });
});

describe("sharing a payment from the dashboard", () => {
  beforeEach(() => vi.resetAllMocks());

  function renderDialog() {
    return render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <CreatePaymentDialog onClose={vi.fn()} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it("sends part of the payment to a connected account", async () => {
    vi.mocked(api.listConnectedAccounts).mockResolvedValue({ object: "list", data: [pizza, { ...pizza, id: "acct_off", name: "Closed", status: "disabled" }], has_more: false });
    vi.mocked(api.createCheckoutSession).mockResolvedValue({ payment_id: "pay_1", client_secret: "s", url: "http://localhost:5174/c/s" });
    renderDialog();

    await userEvent.selectOptions(await screen.findByLabelText("Send part of it to"), "acct_pizza");
    // Disabled accounts are not offered.
    expect(screen.queryByRole("option", { name: "Closed" })).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("The account receives"), "10.00");
    await userEvent.click(screen.getByRole("button", { name: "Create checkout" }));

    await waitFor(() =>
      expect(api.createCheckoutSession).toHaveBeenCalledWith(
        expect.objectContaining({ amount: 2500, transfer_data: [{ destination: "acct_pizza", amount: 1000 }] }),
        false,
      ),
    );
  });

  it("does not let the account receive more than the payment", async () => {
    vi.mocked(api.listConnectedAccounts).mockResolvedValue({ object: "list", data: [pizza], has_more: false });
    renderDialog();

    await userEvent.selectOptions(await screen.findByLabelText("Send part of it to"), "acct_pizza");
    await userEvent.type(screen.getByLabelText("The account receives"), "30.00");
    await userEvent.click(screen.getByRole("button", { name: "Create checkout" }));

    expect(await screen.findByText("The account cannot receive more than the payment")).toBeInTheDocument();
    expect(api.createCheckoutSession).not.toHaveBeenCalled();
  });

  it("offers no sharing when there are no connected accounts", async () => {
    vi.mocked(api.listConnectedAccounts).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderDialog();
    await screen.findByRole("button", { name: "Create checkout" });
    expect(screen.queryByLabelText("Send part of it to")).not.toBeInTheDocument();
  });
});
