import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Order, Product, ShopConfig } from "./api";
import { App } from "./App";
import { CartProvider } from "./cart";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getProducts: vi.fn(),
  getConfig: vi.fn(),
  getOrders: vi.fn(),
  getOrder: vi.fn(),
  createOrder: vi.fn(),
  saveSetup: vi.fn(),
}));

const api = await import("./api");

const mug: Product = { id: "mug", name: "Ceramic mug", description: "A mug", unitAmount: 1200, currency: "EUR" };
const tote: Product = { id: "tote", name: "Canvas tote", description: "A tote", unitAmount: 1850, currency: "EUR" };

const config: ShopConfig = {
  configured: true,
  webhookConfigured: true,
  publishableKey: "pk_test_" + "p".repeat(32),
  checkoutUrl: "http://checkout.test",
  webhookUrl: "http://localhost:4000/webhooks/cutpay",
  allowSetup: true,
};

const order = (over: Partial<Order> = {}): Order => ({
  id: "ord_1",
  lines: [{ productId: "mug", name: "Ceramic mug", quantity: 2, unitAmount: 1200 }],
  amount: 2400,
  currency: "EUR",
  email: null,
  mode: "embedded",
  status: "pending",
  paymentId: "pay_1",
  createdAt: "2026-10-17T10:00:00Z",
  paidAt: null,
  failureReason: null,
  ...over,
});

function renderApp(path = "/") {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <CartProvider>
          <App />
        </CartProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.mocked(api.getProducts).mockResolvedValue([mug, tote]);
  vi.mocked(api.getConfig).mockResolvedValue(config);
  vi.mocked(api.getOrders).mockResolvedValue([]);
});

describe("shopping", () => {
  it("lists products, fills the cart and shows the total", async () => {
    renderApp();
    await userEvent.click(await screen.findByRole("button", { name: "Add Ceramic mug to cart" }));
    await userEvent.click(screen.getByRole("button", { name: "Add Ceramic mug to cart" }));
    await userEvent.click(screen.getByRole("button", { name: "Add Canvas tote to cart" }));

    await userEvent.click(screen.getByRole("link", { name: /Cart/ }));
    expect(await screen.findByTestId("cart-total")).toHaveTextContent("€42.50");
    expect(screen.getByLabelText("Ceramic mug quantity")).toHaveTextContent("2");

    await userEvent.click(screen.getByRole("button", { name: "Remove one Canvas tote" }));
    expect(screen.getByTestId("cart-total")).toHaveTextContent("€24.00");
  });

  it("sends only product ids and quantities to the backend, never prices", async () => {
    vi.mocked(api.createOrder).mockResolvedValue(order());
    vi.mocked(api.getOrder).mockResolvedValue(order({ clientSecret: "pay_1_secret_x" }));
    renderApp();
    await userEvent.click(await screen.findByRole("button", { name: "Add Ceramic mug to cart" }));
    await userEvent.click(screen.getByRole("link", { name: /Cart/ }));
    await userEvent.type(await screen.findByLabelText("Email for your receipt"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: "Pay on this site" }));

    await waitFor(() =>
      expect(api.createOrder).toHaveBeenCalledWith({
        items: [{ productId: "mug", quantity: 1 }],
        mode: "embedded",
        email: "anna@example.com",
      }),
    );
    // The embedded flow continues on the pay page.
    expect(await screen.findByRole("heading", { name: "Pay by card" })).toBeInTheDocument();
  });

  it("shows the server's reason when the order cannot be created", async () => {
    const { ShopApiError } = await import("./api");
    vi.mocked(api.createOrder).mockRejectedValue(new ShopApiError(409, "not_configured", "Add your Cutpay keys on the Settings page first"));
    renderApp();
    await userEvent.click(await screen.findByRole("button", { name: "Add Ceramic mug to cart" }));
    await userEvent.click(screen.getByRole("link", { name: /Cart/ }));
    await userEvent.click(await screen.findByRole("button", { name: "Pay on this site" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Add your Cutpay keys");
  });
});

describe("after paying", () => {
  it("waits for the webhook before saying the order is paid", async () => {
    vi.mocked(api.getOrder).mockResolvedValueOnce(order()).mockResolvedValue(order({ status: "paid", paidAt: "2026-10-17T10:01:00Z" }));
    renderApp("/thanks?order=ord_1");

    expect(await screen.findByRole("heading", { name: "Waiting for confirmation…" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: /Your order is paid/ }, { timeout: 3000 })).toBeInTheDocument();
  });

  it("says so when the payment failed", async () => {
    vi.mocked(api.getOrder).mockResolvedValue(order({ status: "failed", failureReason: "card_declined" }));
    renderApp("/thanks?order=ord_1");
    expect(await screen.findByRole("heading", { name: "Payment did not go through" })).toBeInTheDocument();
  });

  it("lists orders with their status", async () => {
    vi.mocked(api.getOrders).mockResolvedValue([order({ status: "paid" }), order({ id: "ord_2", status: "failed" })]);
    renderApp("/orders");
    expect(await screen.findByText("ord_2")).toBeInTheDocument();
    expect(screen.getByText("paid")).toBeInTheDocument();
    expect(screen.getAllByText("2× Ceramic mug", { selector: "td" })).toHaveLength(2);
  });
});

describe("settings", () => {
  it("saves the keys and clears the secret fields afterwards", async () => {
    vi.mocked(api.saveSetup).mockResolvedValue({ configured: true });
    renderApp("/settings");
    await userEvent.type(await screen.findByLabelText("Secret key"), "sk_test_abc");
    await userEvent.type(screen.getByLabelText("Publishable key"), "pk_test_abc");
    await userEvent.type(screen.getByLabelText("Webhook signing secret"), "whsec_abc");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(api.saveSetup).toHaveBeenCalledWith({ secretKey: "sk_test_abc", publishableKey: "pk_test_abc", webhookSecret: "whsec_abc" }),
    );
    await waitFor(() => expect(screen.getByLabelText("Secret key")).toHaveValue(""));
    expect(screen.getByText("http://localhost:4000/webhooks/cutpay")).toBeInTheDocument();
  });

  it("explains when setup from the browser is off", async () => {
    vi.mocked(api.getConfig).mockResolvedValue({ ...config, allowSetup: false });
    renderApp("/settings");
    expect(await screen.findByRole("alert")).toHaveTextContent("switched off");
    expect(screen.queryByLabelText("Secret key")).not.toBeInTheDocument();
  });
});
