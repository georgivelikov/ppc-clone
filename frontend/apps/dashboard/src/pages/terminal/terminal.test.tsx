import type { Payment } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ChargeQrPage } from "./ChargeQrPage";
import { ReceiptPage } from "./ReceiptPage";
import { TerminalPage } from "./TerminalPage";

const card = vi.hoisted(() => {
  const handlers: Record<string, (payload: unknown) => void> = {};
  const element = {
    handlers,
    on: vi.fn((event: string, handler: (payload: unknown) => void) => {
      handlers[event] = handler;
      return element;
    }),
    mount: vi.fn(),
    unmount: vi.fn(),
    confirm: vi.fn(),
  };
  const created: { secret: string; key: string; url: string }[] = [];
  return { element, created };
});

vi.mock("@cutpay/checkout-js", () => ({
  Elements: class {
    constructor(secret: string, key: string, url: string) {
      card.created.push({ secret, key, url });
    }
    create() {
      return card.element;
    }
  },
}));

vi.mock("../../api", () => ({
  api: {
    createCheckoutSession: vi.fn(),
    createTerminalCharge: vi.fn(),
    checkoutQr: vi.fn(),
    watchPayment: vi.fn(),
    getPayment: vi.fn(),
    cancelPayment: vi.fn(),
  },
}));

vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ role: "owner", merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));

let testMode = true;
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const payment = (over: Partial<Payment> = {}): Payment => ({
  id: "pay_1",
  object: "payment_intent",
  livemode: false,
  amount: 4200,
  currency: "EUR",
  status: "requires_payment_method",
  capture_method: "automatic",
  channel: "moto",
  description: "Phone order #12",
  customer_email: null,
  metadata: {},
  client_secret: "pay_1_secret_x",
  return_url: null,
  amount_refunded: 0,
  failure_code: null,
  failure_message: null,
  created_at: "2026-10-18T10:00:00Z",
  updated_at: "2026-10-18T10:00:00Z",
  attempts: [],
  refunds: [],
  ...over,
});

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/terminal" element={<TerminalPage />} />
          <Route path="/terminal/qr" element={<ChargeQrPage />} />
          <Route path="/terminal/receipt/:id" element={<ReceiptPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  testMode = true;
  card.created.length = 0;
  for (const key of Object.keys(card.element.handlers)) delete card.element.handlers[key];
  card.element.on.mockImplementation((event: string, handler: (payload: unknown) => void) => {
    card.element.handlers[event] = handler;
    return card.element;
  });
  URL.createObjectURL = vi.fn(() => "blob:qr");
  URL.revokeObjectURL = vi.fn();
  vi.mocked(api.checkoutQr).mockResolvedValue(new Blob(["<svg/>"]));
  vi.mocked(api.cancelPayment).mockResolvedValue(payment());
});

describe("Virtual POS", () => {
  async function startCharge() {
    vi.mocked(api.createTerminalCharge).mockResolvedValue(payment());
    renderAt("/terminal");
    await userEvent.type(screen.getByLabelText("Amount"), "42");
    await userEvent.type(screen.getByLabelText("Description (optional)"), "Phone order #12");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await screen.findByLabelText("Card details");
  }

  it("creates a card-not-present charge, then mounts the card element for it", async () => {
    await startCharge();

    expect(api.createTerminalCharge).toHaveBeenCalledWith(
      { amount: 4200, currency: "EUR", description: "Phone order #12", customer_email: undefined, merchant_name: "Rose Cafe" },
      false,
    );
    expect(card.created).toEqual([{ secret: "pay_1_secret_x", key: "", url: "http://localhost:5174" }]);
    expect(card.element.mount).toHaveBeenCalled();
    expect(screen.getByTestId("charge-amount")).toHaveTextContent("€42.00");
    expect(screen.getByText(/will not ask for 3-D Secure/)).toBeInTheDocument();
  });

  it("keeps the charge button off until the card is complete", async () => {
    await startCharge();
    const button = screen.getByRole("button", { name: "Charge €42.00" });
    expect(button).toBeDisabled();
    act(() => card.element.handlers.ready?.(undefined));
    expect(button).toBeDisabled();
    act(() => card.element.handlers.change?.({ complete: true, empty: false, brand: "visa", error: null }));
    expect(button).toBeEnabled();
  });

  it("goes to the receipt once the card is charged", async () => {
    await startCharge();
    act(() => {
      card.element.handlers.ready?.(undefined);
      card.element.handlers.change?.({ complete: true, empty: false, brand: "visa", error: null });
    });
    card.element.confirm.mockResolvedValue({ ok: true, session: { id: "pay_1", status: "succeeded" } });
    vi.mocked(api.getPayment).mockResolvedValue(payment({ status: "succeeded", attempts: [] }));

    await userEvent.click(screen.getByRole("button", { name: "Charge €42.00" }));

    expect(card.element.confirm).toHaveBeenCalledWith("pay_1_secret_x", undefined);
    expect(await screen.findByTestId("receipt-amount")).toHaveTextContent("€42.00");
  });

  it("explains a decline and only offers a new charge, because a failed payment is over", async () => {
    await startCharge();
    act(() => {
      card.element.handlers.ready?.(undefined);
      card.element.handlers.change?.({ complete: true, empty: false, brand: "visa", error: null });
    });
    card.element.confirm.mockResolvedValue({ ok: true, session: { id: "pay_1", status: "failed", failure_message: "Your card was declined." } });

    await userEvent.click(screen.getByRole("button", { name: "Charge €42.00" }));

    expect(await screen.findByText("Your card was declined.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Charge/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Start a new charge" }));
    expect(await screen.findByLabelText("Amount")).toBeInTheDocument();
  });

  it("shows the element's own error when it cannot take the payment", async () => {
    await startCharge();
    act(() => {
      card.element.handlers.ready?.(undefined);
      card.element.handlers.change?.({ complete: true, empty: false, brand: "visa", error: null });
    });
    card.element.confirm.mockResolvedValue({ ok: false, error: { type: "api_error", code: "timeout", message: "The payment took too long." } });
    await userEvent.click(screen.getByRole("button", { name: "Charge €42.00" }));
    expect(await screen.findByText("The payment took too long.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Charge €42.00" })).toBeEnabled();
  });

  it("cancels the unpaid charge when the merchant backs out", async () => {
    await startCharge();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(api.cancelPayment).toHaveBeenCalledWith("pay_1");
    expect(await screen.findByLabelText("Amount")).toBeInTheDocument();
  });

  it("validates the form before creating anything, and charges live when test mode is off", async () => {
    renderAt("/terminal");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText("Enter an amount, like 12.50")).toBeInTheDocument();
    expect(api.createTerminalCharge).not.toHaveBeenCalled();
  });

  it("charges in live mode when test mode is off", async () => {
    testMode = false;
    vi.mocked(api.createTerminalCharge).mockResolvedValue(payment({ livemode: true }));
    renderAt("/terminal");
    await userEvent.type(screen.getByLabelText("Amount"), "5");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await waitFor(() => expect(api.createTerminalCharge).toHaveBeenCalled());
    expect(vi.mocked(api.createTerminalCharge).mock.calls[0]?.[1]).toBe(true);
  });
});

describe("Receipt", () => {
  it("shows the card, the method and a reference", async () => {
    vi.mocked(api.getPayment).mockResolvedValue(
      payment({
        status: "succeeded",
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
            created_at: "2026-10-18T10:00:00Z",
          },
        ],
      }),
    );
    renderAt("/terminal/receipt/pay_1");
    expect(await screen.findByTestId("receipt-amount")).toHaveTextContent("€42.00");
    expect(screen.getByText("Visa •••• 4242")).toBeInTheDocument();
    expect(screen.getByText("Card not present (phone or mail order)")).toBeInTheDocument();
    expect(screen.getByText("pay_1")).toBeInTheDocument();
    expect(screen.getByText(/Test payment/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Print receipt/ })).toBeInTheDocument();
  });
});

describe("Charge with QR", () => {
  async function showQr() {
    vi.mocked(api.createCheckoutSession).mockResolvedValue({ payment_id: "pay_7", client_secret: "pay_7_secret_x", url: "http://localhost:5174/c/pay_7_secret_x" });
    renderAt("/terminal/qr");
    await userEvent.type(screen.getByLabelText("Amount"), "12.50");
    await userEvent.type(screen.getByLabelText("What is it for? (optional)"), "Table 4");
    await userEvent.click(screen.getByRole("button", { name: "Show QR code" }));
    await screen.findByRole("dialog", { name: "Charge with QR" });
  }

  it("shows the code full screen and turns into a big tick the moment the stream says paid", async () => {
    let push: (event: { status: string }) => void = () => undefined;
    vi.mocked(api.watchPayment).mockImplementation((_id, onStatus) => {
      push = onStatus;
      return new Promise(() => undefined);
    });
    await showQr();

    expect(api.createCheckoutSession).toHaveBeenCalledWith(
      { amount: 1250, currency: "EUR", description: "Table 4", merchant_name: "Rose Cafe" },
      false,
    );
    expect(api.checkoutQr).toHaveBeenCalledWith("http://localhost:5174/c/pay_7_secret_x");
    expect(await screen.findByAltText("QR code to pay")).toBeInTheDocument();
    expect(screen.getByTestId("qr-amount")).toHaveTextContent("€12.50");
    expect(api.watchPayment).toHaveBeenCalledWith("pay_7", expect.any(Function), expect.any(AbortSignal));

    act(() => push({ status: "requires_payment_method" }));
    expect(screen.queryByText("Paid")).not.toBeInTheDocument();
    act(() => push({ status: "succeeded" }));

    expect(await screen.findByText("Paid")).toBeInTheDocument();
    expect(screen.queryByAltText("QR code to pay")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New charge" })).toBeInTheDocument();
  });

  it("shows a failure with its reason", async () => {
    vi.mocked(api.watchPayment).mockImplementation(async (_id, onStatus) => {
      onStatus({ status: "failed", failure_code: "card_declined" } as never);
    });
    await showQr();
    expect(await screen.findByText("Payment failed")).toBeInTheDocument();
    expect(screen.getByText("card declined")).toBeInTheDocument();
  });

  it("falls back to reading the payment when the stream drops", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(api.watchPayment).mockRejectedValue(new Error("network"));
      vi.mocked(api.getPayment).mockResolvedValue(payment({ id: "pay_7", status: "succeeded" }));
      await showQr();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000);
      });
      expect(await screen.findByText("Paid")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("cancels the charge when the merchant closes the screen before it is paid", async () => {
    vi.mocked(api.watchPayment).mockImplementation(() => new Promise(() => undefined));
    await showQr();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(api.cancelPayment).toHaveBeenCalledWith("pay_7");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("does not create anything for an invalid amount", async () => {
    renderAt("/terminal/qr");
    await userEvent.type(screen.getByLabelText("Amount"), "abc");
    await userEvent.click(screen.getByRole("button", { name: "Show QR code" }));
    expect(await screen.findByText("Enter an amount, like 12.50")).toBeInTheDocument();
    expect(api.createCheckoutSession).not.toHaveBeenCalled();
  });
});
