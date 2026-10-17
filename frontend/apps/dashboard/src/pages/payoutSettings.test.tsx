import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PayoutSettingsPage } from "./PayoutSettingsPage";

vi.mock("../api", () => ({
  api: {
    listBankAccounts: vi.fn(),
    createBankAccount: vi.fn(),
    deleteBankAccount: vi.fn(),
    getPayoutSchedule: vi.fn(),
    updatePayoutSchedule: vi.fn(),
  },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" }, role: "owner" }),
}));
vi.mock("../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../api");

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/settings/payouts"]}>
        <PayoutSettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PayoutSettingsPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.listBankAccounts).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.getPayoutSchedule).mockResolvedValue({
      object: "payout_schedule",
      schedule: "daily",
      delay_days: 2,
      weekly_anchor: "MONDAY",
    });
  });

  it("rejects an IBAN with a bad checksum before calling the server", async () => {
    renderPage();
    await userEvent.type(await screen.findByLabelText("IBAN"), "BG80BNBG96611020345679");
    await userEvent.type(screen.getByLabelText("Account holder"), "Rose Cafe Ltd");
    await userEvent.click(screen.getByRole("button", { name: "Add bank account" }));

    expect(await screen.findByText(/This IBAN is not valid/)).toBeInTheDocument();
    expect(api.createBankAccount).not.toHaveBeenCalled();
  });

  it("adds a bank account with a valid IBAN, spaces and all", async () => {
    vi.mocked(api.createBankAccount).mockResolvedValue({
      id: "ba_1",
      object: "bank_account",
      country: "BG",
      iban_last4: "5678",
      holder_name: "Rose Cafe Ltd",
      currency: "EUR",
      status: "active",
      livemode: false,
      created: "2026-10-17T10:00:00Z",
    });
    renderPage();
    await userEvent.type(await screen.findByLabelText("IBAN"), "BG80 BNBG 9661 1020 3456 78");
    await userEvent.type(screen.getByLabelText("Account holder"), "Rose Cafe Ltd");
    await userEvent.click(screen.getByRole("button", { name: "Add bank account" }));

    await vi.waitFor(() =>
      expect(api.createBankAccount).toHaveBeenCalledWith(
        { iban: "BG80 BNBG 9661 1020 3456 78", holder_name: "Rose Cafe Ltd", currency: "EUR" },
        false,
      ),
    );
  });

  it("shows existing accounts masked and removes one", async () => {
    vi.mocked(api.listBankAccounts).mockResolvedValue({
      object: "list",
      data: [
        {
          id: "ba_1",
          object: "bank_account",
          country: "BG",
          iban_last4: "5678",
          holder_name: "Rose Cafe Ltd",
          currency: "EUR",
          status: "active",
          livemode: false,
          created: "2026-10-17T10:00:00Z",
        },
      ],
    });
    vi.mocked(api.deleteBankAccount).mockResolvedValue(undefined);
    renderPage();

    expect(await screen.findByText(/BG•• •••• 5678/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Remove account ending 5678" }));

    await vi.waitFor(() => expect(api.deleteBankAccount).toHaveBeenCalledWith("ba_1"));
  });

  it("saves a weekly schedule with a payout day", async () => {
    vi.mocked(api.updatePayoutSchedule).mockResolvedValue({
      object: "payout_schedule",
      schedule: "weekly",
      delay_days: 2,
      weekly_anchor: "FRIDAY",
    });
    renderPage();

    await userEvent.selectOptions(await screen.findByLabelText("Pay out"), "weekly");
    await userEvent.selectOptions(screen.getByLabelText("Weekly payout day"), "FRIDAY");
    await userEvent.click(screen.getByRole("button", { name: "Save schedule" }));

    await vi.waitFor(() =>
      expect(api.updatePayoutSchedule).toHaveBeenCalledWith({
        schedule: "weekly",
        weekly_anchor: "FRIDAY",
        delay_days: 2,
      }),
    );
  });

  it("rejects a settlement delay outside 0 to 30 days", async () => {
    renderPage();
    const delay = await screen.findByLabelText("Funds become available after (days)");
    await userEvent.clear(delay);
    await userEvent.type(delay, "45");
    await userEvent.click(screen.getByRole("button", { name: "Save schedule" }));

    expect(await screen.findByText("At most 30")).toBeInTheDocument();
    expect(api.updatePayoutSchedule).not.toHaveBeenCalled();
  });
});
