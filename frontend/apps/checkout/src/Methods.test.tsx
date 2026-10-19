import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Session } from "./api";
import { CheckoutPage } from "./CheckoutPage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSession: vi.fn(),
  getMethods: vi.fn(),
  confirm: vi.fn(),
  confirmBlik: vi.fn(),
  getWalletCards: vi.fn(),
  authenticateWallet: vi.fn(),
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
  amount: 12000,
  currency: "PLN",
  description: null,
  merchant_name: "Sklep",
  return_url: null,
  failure_code: null,
  failure_message: null,
  next_action: null,
};

function renderCheckout() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/c/pay_1_secret_x"]}>
        <Routes>
          <Route path="/c/:secret" element={<CheckoutPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("other ways of paying", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.getSession).mockResolvedValue(base);
    vi.mocked(bnplApi.getBnplOffers).mockRejectedValue(new Error("no providers"));
    vi.mocked(api.getMethods).mockResolvedValue({ methods: ["card", "apple_pay", "google_pay", "blik"] });
    vi.mocked(api.getWalletCards).mockResolvedValue([
      { id: "visa", label: "Everyday Visa", brand: "visa", last4: "4242" },
      { id: "secure", label: "Visa that asks for 3-D Secure", brand: "visa", last4: "3220" },
    ]);
  });

  it("offers a wallet and BLIK next to the card, and only the card when nothing else is allowed", async () => {
    renderCheckout();
    expect(await screen.findByRole("tab", { name: "BLIK" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Wallet" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Card" })).toHaveAttribute("aria-selected", "true");
  });

  it("shows no tabs for a payment that only takes cards", async () => {
    vi.mocked(api.getMethods).mockResolvedValue({ methods: ["card"] });
    renderCheckout();
    expect(await screen.findByRole("form", { name: "Payment details" })).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  it("falls back to the card form when the methods cannot be read", async () => {
    vi.mocked(api.getMethods).mockRejectedValue(new Error("down"));
    renderCheckout();
    expect(await screen.findByRole("form", { name: "Payment details" })).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  it("sends the BLIK code and waits for the bank app", async () => {
    const waiting: Session = { ...base, status: "requires_action", next_action: { type: "blik", challenge_id: "blik_1" } };
    vi.mocked(api.confirmBlik).mockResolvedValue(waiting);
    // The page asks again while it waits, and the bank has not answered yet.
    vi.mocked(api.getSession).mockResolvedValueOnce(base).mockResolvedValue(waiting);
    renderCheckout();
    await userEvent.click(await screen.findByRole("tab", { name: "BLIK" }));
    const form = screen.getByRole("form", { name: "Pay with BLIK" });

    await userEvent.type(within(form).getByLabelText("Email"), "anna@example.com");
    await userEvent.type(within(form).getByLabelText("BLIK code"), "77712a3");
    expect(within(form).getByLabelText("BLIK code")).toHaveValue("777123");
    await userEvent.click(within(form).getByRole("button", { name: /^Pay/ }));

    expect(api.confirmBlik).toHaveBeenCalledWith("pay_1_secret_x", "777123", "anna@example.com");
    expect(await screen.findByRole("heading", { name: "Confirm in your banking app" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the pretend bank app" })).toHaveAttribute("href", "/vault/sim/blik-app");
  });

  it("asks for a six digit code before it sends anything", async () => {
    renderCheckout();
    await userEvent.click(await screen.findByRole("tab", { name: "BLIK" }));
    const form = screen.getByRole("form", { name: "Pay with BLIK" });
    await userEvent.type(within(form).getByLabelText("Email"), "anna@example.com");
    await userEvent.type(within(form).getByLabelText("BLIK code"), "123");
    await userEvent.click(within(form).getByRole("button", { name: /^Pay/ }));

    expect(await within(form).findByText("Enter the six digit code from your banking app")).toBeInTheDocument();
    expect(api.confirmBlik).not.toHaveBeenCalled();
  });

  it("lets the customer type another code when the bank says it is not valid", async () => {
    const { CheckoutApiError } = await import("./api");
    vi.mocked(api.confirmBlik).mockRejectedValue(new CheckoutApiError(400, "invalid_blik_code", "not valid"));
    renderCheckout();
    await userEvent.click(await screen.findByRole("tab", { name: "BLIK" }));
    const form = screen.getByRole("form", { name: "Pay with BLIK" });
    await userEvent.type(within(form).getByLabelText("Email"), "anna@example.com");
    await userEvent.type(within(form).getByLabelText("BLIK code"), "123456");
    await userEvent.click(within(form).getByRole("button", { name: /^Pay/ }));

    expect(await within(form).findByText(/That code is not valid or has expired/)).toBeInTheDocument();
    expect(within(form).getByRole("button", { name: /^Pay/ })).toBeEnabled();
  });

  it("pays with a wallet: pick a card, confirm on the device, and the token is what gets confirmed", async () => {
    vi.mocked(api.authenticateWallet).mockResolvedValue({ token: "tok_wallet" });
    vi.mocked(api.confirm).mockResolvedValue({ ...base, status: "succeeded" });
    renderCheckout();
    await userEvent.click(await screen.findByRole("tab", { name: "Wallet" }));
    await userEvent.click(screen.getByRole("button", { name: "Pay with Google Pay" }));

    const sheet = await screen.findByRole("dialog", { name: "Google Pay (pretend)" });
    await userEvent.click(await within(sheet).findByRole("radio", { name: /Visa that asks for 3-D Secure/ }));
    await userEvent.type(within(sheet).getByLabelText("Email"), "anna@example.com");
    await userEvent.click(within(sheet).getByRole("button", { name: "Confirm with Face ID or PIN" }));

    expect(api.authenticateWallet).toHaveBeenCalledWith("google_pay", "secure");
    expect(api.confirm).toHaveBeenCalledWith("pay_1_secret_x", "tok_wallet", "anna@example.com");
    expect(await screen.findByRole("heading", { name: "Payment successful" })).toBeInTheDocument();
  });

  it("closes the wallet sheet without paying", async () => {
    renderCheckout();
    await userEvent.click(await screen.findByRole("tab", { name: "Wallet" }));
    await userEvent.click(screen.getByRole("button", { name: "Pay with Apple Pay" }));
    await userEvent.click(await screen.findByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(api.confirm).not.toHaveBeenCalled();
  });
});
