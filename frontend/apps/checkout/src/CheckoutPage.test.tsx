import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Session } from "./api";
import { CheckoutPage } from "./CheckoutPage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSession: vi.fn(),
  tokenize: vi.fn(),
  confirm: vi.fn(),
  completeThreeDs: vi.fn(),
}));

const api = await import("./api");

const base: Session = {
  id: "pay_1",
  status: "requires_payment_method",
  amount: 2500,
  currency: "EUR",
  description: "Flat white x2",
  merchant_name: "Rose Cafe",
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

async function fillCard(number: string) {
  await userEvent.type(screen.getByLabelText("Email"), "anna@example.com");
  await userEvent.type(screen.getByLabelText("Card number"), number);
  await userEvent.type(screen.getByLabelText("Expiry"), "1299");
  await userEvent.type(screen.getByLabelText("CVC"), "123");
}

describe("hosted checkout", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("shows the order summary and a pay button with the amount", async () => {
    vi.mocked(api.getSession).mockResolvedValue(base);
    renderCheckout();

    expect(await screen.findByRole("button", { name: /^Pay/ })).toHaveTextContent("€25.00");
    expect(screen.getByText("Rose Cafe")).toBeInTheDocument();
    expect(screen.getByText("Flat white x2")).toBeInTheDocument();
  });

  it("validates the card before calling the API", async () => {
    vi.mocked(api.getSession).mockResolvedValue(base);
    renderCheckout();
    await userEvent.type(await screen.findByLabelText("Card number"), "4242424242424241");
    await userEvent.click(screen.getByRole("button", { name: /^Pay/ }));

    expect(await screen.findByText("Enter a valid card number")).toBeInTheDocument();
    expect(screen.getByText("Enter your email")).toBeInTheDocument();
    expect(api.tokenize).not.toHaveBeenCalled();
  });

  it("sends the number to the vault and only the token to payments", async () => {
    vi.mocked(api.getSession).mockResolvedValue(base);
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirm).mockResolvedValue({ ...base, status: "succeeded" });
    renderCheckout();
    await screen.findByLabelText("Card number");
    await fillCard("4242424242424242");
    await userEvent.click(screen.getByRole("button", { name: /^Pay/ }));

    expect(await screen.findByRole("heading", { name: "Payment successful" })).toBeInTheDocument();
    expect(api.tokenize).toHaveBeenCalledWith(
      expect.objectContaining({ number: "4242 4242 4242 4242", exp_month: 12, exp_year: 2099 }),
    );
    expect(api.confirm).toHaveBeenCalledWith("pay_1_secret_x", "tok_1", "anna@example.com");
  });

  it("shows the decline reason when the payment fails", async () => {
    vi.mocked(api.getSession).mockResolvedValue(base);
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirm).mockResolvedValue({
      ...base,
      status: "failed",
      failure_code: "card_declined",
      failure_message: "Your card was declined.",
    });
    renderCheckout();
    await screen.findByLabelText("Card number");
    await fillCard("4000000000000002");
    await userEvent.click(screen.getByRole("button", { name: /^Pay/ }));

    expect(await screen.findByRole("heading", { name: "Payment failed" })).toBeInTheDocument();
    expect(screen.getByText("Your card was declined.")).toBeInTheDocument();
  });

  it("asks for the bank code when 3DS is required and then shows the result", async () => {
    vi.mocked(api.getSession)
      .mockResolvedValueOnce({
        ...base,
        status: "requires_action",
        next_action: { type: "three_ds", challenge_id: "auth_1" },
      })
      .mockResolvedValue({ ...base, status: "succeeded" });
    vi.mocked(api.completeThreeDs).mockResolvedValue({ status: "approved" });
    renderCheckout();

    await userEvent.type(await screen.findByLabelText("One-time code"), "1234");
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("heading", { name: "Payment successful" })).toBeInTheDocument();
    expect(api.completeThreeDs).toHaveBeenCalledWith("auth_1", "1234");
  });

  it("explains when the link is not valid", async () => {
    vi.mocked(api.getSession).mockRejectedValue(new api.CheckoutApiError(404, "session_not_found", "nope"));
    renderCheckout();

    expect(await screen.findByRole("heading", { name: "Payment link not found" })).toBeInTheDocument();
  });
});
