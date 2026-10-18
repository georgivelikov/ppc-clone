import type { Invoice, Price, Product, Subscription } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InvoiceDetailPage } from "./InvoiceDetailPage";
import { InvoiceEditorPage, draftTotals } from "./InvoiceEditorPage";
import { ProductsPage } from "./ProductsPage";
import { SubscriptionDetailPage } from "./SubscriptionDetailPage";
import { SubscriptionsPage } from "./SubscriptionsPage";

vi.mock("../../api", () => ({
  api: {
    listProducts: vi.fn(),
    listPrices: vi.fn(),
    createProduct: vi.fn(),
    createPrice: vi.fn(),
    updateProduct: vi.fn(),
    updatePrice: vi.fn(),
    listSubscriptions: vi.fn(),
    subscriptionStats: vi.fn(),
    getSubscription: vi.fn(),
    upcomingInvoice: vi.fn(),
    listInvoices: vi.fn(),
    billingNotifications: vi.fn(),
    previewSubscriptionChange: vi.fn(),
    updateSubscription: vi.fn(),
    cancelSubscription: vi.fn(),
    pauseSubscription: vi.fn(),
    resumeSubscription: vi.fn(),
    getBillingSettings: vi.fn(),
    updateBillingSettings: vi.fn(),
    listCustomers: vi.fn(),
    createInvoice: vi.fn(),
    finalizeInvoice: vi.fn(),
    getInvoice: vi.fn(),
    payInvoice: vi.fn(),
    voidInvoice: vi.fn(),
    deleteInvoice: vi.fn(),
    invoicePdf: vi.fn(),
  },
}));
vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Coffee Club", default_currency: "EUR" } }),
}));
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const NOW = "2026-10-18T10:00:00Z";
const product = (id: string, name: string): Product => ({ id, object: "product", livemode: false, name, description: null, image_url: null, active: true, metadata: {}, created_at: NOW });
const price = (id: string, productId: string, amount: number, over: Partial<Price> = {}): Price => ({
  id,
  object: "price",
  livemode: false,
  product: productId,
  unit_amount: amount,
  currency: "EUR",
  type: "recurring",
  interval: "month",
  interval_count: 1,
  trial_days: 0,
  nickname: null,
  active: true,
  created_at: NOW,
  ...over,
});
const subscription = (over: Partial<Subscription> = {}): Subscription => ({
  id: "sub_1",
  object: "subscription",
  livemode: false,
  customer: "cus_1",
  customer_email: "anna@example.com",
  customer_name: "Anna Petrova",
  status: "active",
  currency: "EUR",
  interval: "month",
  interval_count: 1,
  collection_method: "charge_automatically",
  default_payment_method: null,
  current_period_start: "2026-10-01T00:00:00Z",
  current_period_end: "2026-11-01T00:00:00Z",
  trial_end: null,
  cancel_at_period_end: false,
  collection_paused: false,
  canceled_at: null,
  ended_at: null,
  tax_rate_percent: 0,
  latest_invoice: "in_1",
  metadata: {},
  items: [{ id: "si_1", price: "price_basic", product: "prod_1", product_name: "Basic", nickname: null, unit_amount: 1000, quantity: 1, amount: 1000 }],
  created_at: NOW,
  ...over,
});
const invoice = (over: Partial<Invoice> = {}): Invoice => ({
  id: "in_1",
  object: "invoice",
  livemode: false,
  number: "INV-0001",
  status: "open",
  subscription: null,
  customer: "cus_1",
  customer_email: "anna@example.com",
  customer_name: "Anna Petrova",
  billing_reason: "manual",
  collection_method: "send_invoice",
  currency: "EUR",
  subtotal: 10000,
  tax: 0,
  total: 10000,
  amount_paid: 0,
  amount_due: 10000,
  description: null,
  due_date: null,
  period_start: null,
  period_end: null,
  payment_intent: null,
  failure_code: null,
  attempt_count: 0,
  next_payment_attempt: null,
  hosted_invoice_url: "http://localhost:5174/i/tok",
  invoice_pdf: "/v1/invoices/in_1/pdf",
  lines: [{ id: "il_1", description: "Catering", unit_amount: 10000, quantity: 1, amount: 10000, price: null, proration: false, period_start: null, period_end: null }],
  attempts: [],
  created_at: NOW,
  finalized_at: NOW,
  paid_at: null,
  ...over,
});

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/products" element={<ProductsPage />} />
          <Route path="/subscriptions" element={<SubscriptionsPage />} />
          <Route path="/subscriptions/:id" element={<SubscriptionDetailPage />} />
          <Route path="/invoices/new" element={<InvoiceEditorPage />} />
          <Route path="/invoices/:id" element={<InvoiceDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("products", () => {
  beforeEach(() => vi.resetAllMocks());

  it("lists each product with its prices in words", async () => {
    vi.mocked(api.listProducts).mockResolvedValue({ object: "list", data: [product("prod_1", "Coffee club")], has_more: false });
    vi.mocked(api.listPrices).mockResolvedValue({
      object: "list",
      data: [price("price_a", "prod_1", 1500, { trial_days: 7 }), price("price_b", "prod_1", 36000, { interval: "year" }), price("price_c", "prod_1", 4000, { interval_count: 3 })],
      has_more: false,
    });
    renderAt("/products");

    await screen.findByRole("heading", { name: "Coffee club" });
    const labels = screen.getAllByTestId("price-label").map((el) => el.textContent);
    expect(labels).toEqual(["€15.00 every month", "€360.00 every year", "€40.00 every 3 months"]);
    expect(screen.getByText("7-day trial")).toBeInTheDocument();
  });

  it("creates a product with a monthly price and a trial in one go", async () => {
    vi.mocked(api.listProducts).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.listPrices).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.createProduct).mockResolvedValue(product("prod_9", "Coffee club"));
    vi.mocked(api.createPrice).mockResolvedValue(price("price_9", "prod_9", 1500));
    renderAt("/products");

    await userEvent.click(await screen.findByRole("button", { name: "New product" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Create product" }));
    expect(await within(dialog).findByText("Give the product a name")).toBeInTheDocument();
    expect(within(dialog).getByText("Enter a price, like 15.00")).toBeInTheDocument();
    expect(api.createProduct).not.toHaveBeenCalled();

    await userEvent.type(within(dialog).getByLabelText("Name"), "Coffee club");
    await userEvent.type(within(dialog).getByLabelText("Price"), "15.00");
    await userEvent.clear(within(dialog).getByLabelText("Free trial (days)"));
    await userEvent.type(within(dialog).getByLabelText("Free trial (days)"), "7");
    await userEvent.click(within(dialog).getByRole("button", { name: "Create product" }));

    await waitFor(() =>
      expect(api.createPrice).toHaveBeenCalledWith(
        { product: "prod_9", unit_amount: 1500, currency: "EUR", interval: "month", interval_count: 1, trial_days: 7, nickname: undefined },
        false,
      ),
    );
  });
});

describe("subscriptions", () => {
  beforeEach(() => vi.resetAllMocks());

  it("shows recurring revenue, subscriber counts and churn above the list", async () => {
    vi.mocked(api.subscriptionStats).mockResolvedValue({
      object: "subscription_stats",
      mrr: { EUR: 250000 },
      by_status: { active: 18, trialing: 3, past_due: 2 },
      ended_last_30_days: 2,
    });
    vi.mocked(api.listSubscriptions).mockResolvedValue({ object: "list", data: [subscription()], has_more: false });
    vi.mocked(api.getBillingSettings).mockResolvedValue({ object: "billing_settings", retry_days: [1, 3, 5, 7], final_action: "cancel" });
    renderAt("/subscriptions");

    await waitFor(() => expect(screen.getByTestId("mrr")).toHaveTextContent("€2,500.00"));
    expect(screen.getByTestId("count-active")).toHaveTextContent("18");
    expect(screen.getByTestId("count-trial-past-due")).toHaveTextContent("3 · 2");
    // 2 of the 22 paying subscribers of the month ended (20 still pay).
    expect(screen.getByTestId("churn")).toHaveTextContent("9.1%");
    expect(await screen.findByText("Anna Petrova")).toBeInTheDocument();
  });

  it("checks the retry days before saving them", async () => {
    vi.mocked(api.subscriptionStats).mockResolvedValue({ object: "subscription_stats", mrr: {}, by_status: {}, ended_last_30_days: 0 });
    vi.mocked(api.listSubscriptions).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.getBillingSettings).mockResolvedValue({ object: "billing_settings", retry_days: [1, 3, 5, 7], final_action: "cancel" });
    vi.mocked(api.updateBillingSettings).mockResolvedValue({ object: "billing_settings", retry_days: [2, 4], final_action: "unpaid" });
    renderAt("/subscriptions");

    const days = await screen.findByLabelText("Retry on day");
    await waitFor(() => expect(days).toHaveValue("1, 3, 5, 7"));
    await userEvent.clear(days);
    await userEvent.type(days, "5, 2");
    await userEvent.click(screen.getByRole("button", { name: "Save rules" }));
    expect(await screen.findByText(/increasing whole days/)).toBeInTheDocument();
    expect(api.updateBillingSettings).not.toHaveBeenCalled();

    await userEvent.clear(days);
    await userEvent.type(days, "2, 4");
    await userEvent.selectOptions(screen.getByLabelText("After the last retry fails"), "unpaid");
    await userEvent.click(screen.getByRole("button", { name: "Save rules" }));
    await waitFor(() => expect(api.updateBillingSettings).toHaveBeenCalledWith({ retry_days: [2, 4], final_action: "unpaid" }));
  });

  function mockDetail(over: Partial<Subscription> = {}) {
    vi.mocked(api.getSubscription).mockResolvedValue(subscription(over));
    vi.mocked(api.upcomingInvoice).mockResolvedValue({
      object: "upcoming_invoice",
      currency: "EUR",
      period_start: "2026-11-01T00:00:00Z",
      period_end: "2026-12-01T00:00:00Z",
      subtotal: 1000,
      tax: 0,
      total: 1000,
      lines: [{ id: "il_u", description: "Basic", unit_amount: 1000, quantity: 1, amount: 1000, price: "price_basic", proration: false, period_start: null, period_end: null }],
    });
    vi.mocked(api.listInvoices).mockResolvedValue({ object: "list", data: [invoice({ subscription: "sub_1", billing_reason: "subscription_create", status: "paid" })], has_more: false });
    vi.mocked(api.billingNotifications).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.listPrices).mockResolvedValue({
      object: "list",
      data: [price("price_basic", "prod_1", 1000), price("price_pro", "prod_2", 3000), price("price_year", "prod_3", 9000, { interval: "year" })],
      has_more: false,
    });
    vi.mocked(api.listProducts).mockResolvedValue({ object: "list", data: [product("prod_1", "Basic"), product("prod_2", "Pro"), product("prod_3", "Yearly")], has_more: false });
  }

  it("previews the proration of a plan change before applying it", async () => {
    mockDetail();
    vi.mocked(api.previewSubscriptionChange).mockResolvedValue({
      currency: "EUR",
      period_end: "2026-11-01T00:00:00Z",
      net: 1000,
      lines: [
        { description: "Unused time on Basic × 1 after 16 Oct 2026", unit_amount: 1000, quantity: 1, amount: -500 },
        { description: "Remaining time on Pro × 1 after 16 Oct 2026", unit_amount: 3000, quantity: 1, amount: 1500 },
      ],
    });
    vi.mocked(api.updateSubscription).mockResolvedValue(subscription());
    renderAt("/subscriptions/sub_1");

    await userEvent.click(await screen.findByRole("button", { name: "Change plan" }));
    const dialog = await screen.findByRole("dialog");
    // Only prices with the same interval can replace the plan, so the yearly one is not offered.
    await waitFor(() => expect(within(dialog).getByRole("option", { name: /Pro/ })).toBeInTheDocument());
    expect(within(dialog).queryByRole("option", { name: /Yearly/ })).not.toBeInTheDocument();

    await userEvent.selectOptions(within(dialog).getByLabelText("Plan"), "price_pro");
    const preview = await within(dialog).findByTestId("proration-preview");
    expect(within(preview).getByText(/Unused time on Basic/)).toBeInTheDocument();
    expect(within(dialog).getByTestId("proration-net")).toHaveTextContent("€10.00");
    expect(api.previewSubscriptionChange).toHaveBeenCalledWith("sub_1", [{ price: "price_pro", quantity: 1 }]);

    await userEvent.click(within(dialog).getByLabelText(/Charge the difference now/));
    await userEvent.click(within(dialog).getByRole("button", { name: "Change plan" }));
    await waitFor(() =>
      expect(api.updateSubscription).toHaveBeenCalledWith("sub_1", {
        items: [{ price: "price_pro", quantity: 1 }],
        proration_behavior: "create_prorations",
        bill_immediately: true,
      }),
    );
  });

  it("says when a change would credit the customer and when nothing changed", async () => {
    mockDetail();
    vi.mocked(api.previewSubscriptionChange).mockResolvedValue({
      currency: "EUR",
      period_end: "2026-11-01T00:00:00Z",
      net: -1000,
      lines: [{ description: "Unused time on Pro", unit_amount: 3000, quantity: 1, amount: -1500 }],
    });
    renderAt("/subscriptions/sub_1");

    await userEvent.click(await screen.findByRole("button", { name: "Change plan" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Change plan" }));
    expect(await within(dialog).findByText("Choose a different plan or quantity")).toBeInTheDocument();

    await userEvent.clear(within(dialog).getByLabelText("Quantity"));
    await userEvent.type(within(dialog).getByLabelText("Quantity"), "2");
    expect(await within(dialog).findByText("Credit for the rest of the period")).toBeInTheDocument();
  });

  it("cancels at the end of the period, or offers to keep a subscription that is set to end", async () => {
    mockDetail();
    vi.mocked(api.cancelSubscription).mockResolvedValue(subscription({ cancel_at_period_end: true }));
    renderAt("/subscriptions/sub_1");

    await userEvent.click(await screen.findByRole("button", { name: "Cancel" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel at the end of the period" }));
    await waitFor(() => expect(api.cancelSubscription).toHaveBeenCalledWith("sub_1", true));
  });

  it("shows a set-to-end subscription with a way back, and a paused one with resume", async () => {
    mockDetail({ cancel_at_period_end: true, collection_paused: true });
    vi.mocked(api.updateSubscription).mockResolvedValue(subscription());
    renderAt("/subscriptions/sub_1");

    expect(await screen.findByText(/This subscription ends on/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resume" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Keep it" }));
    await waitFor(() => expect(api.updateSubscription).toHaveBeenCalledWith("sub_1", { cancel_at_period_end: false }));
  });
});

describe("invoices", () => {
  beforeEach(() => vi.resetAllMocks());

  it("works out subtotal, tax and total like the server does", () => {
    expect(draftTotals([{ unit: 10000, quantity: 2 }, { unit: -1000, quantity: 1 }], 20)).toEqual({ subtotal: 19000, tax: 3800, total: 22800 });
    // Tax is rounded half up and a discount larger than the charges is not taxed.
    expect(draftTotals([{ unit: 1005, quantity: 1 }], 10)).toEqual({ subtotal: 1005, tax: 101, total: 1106 });
    expect(draftTotals([{ unit: -500, quantity: 1 }], 20)).toEqual({ subtotal: -500, tax: 0, total: -500 });
    expect(draftTotals([{ unit: null, quantity: 3 }], 0).total).toBe(0);
  });

  it("builds an invoice from lines with a discount and sends it", async () => {
    vi.mocked(api.listCustomers).mockResolvedValue({
      object: "list",
      has_more: false,
      data: [{ id: "cus_1", object: "customer", livemode: false, email: "anna@example.com", name: "Anna Petrova", phone: null, description: null, address: null, metadata: {}, default_payment_method: null, created_at: NOW }],
    });
    vi.mocked(api.createInvoice).mockResolvedValue(invoice({ status: "draft", number: null }));
    vi.mocked(api.finalizeInvoice).mockResolvedValue(invoice());
    vi.mocked(api.getInvoice).mockResolvedValue(invoice());
    vi.mocked(api.billingNotifications).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.invoicePdf).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
    renderAt("/invoices/new");

    await userEvent.click(await screen.findByRole("button", { name: "Create and send" }));
    expect(await screen.findByText("Choose a customer")).toBeInTheDocument();
    expect(screen.getByText("Describe the line")).toBeInTheDocument();
    expect(api.createInvoice).not.toHaveBeenCalled();

    await userEvent.click(await screen.findByRole("button", { name: /Anna Petrova/ }));
    await userEvent.type(screen.getByLabelText("Description 1"), "Catering");
    await userEvent.type(screen.getByLabelText("Unit price 1"), "100.00");
    await userEvent.clear(screen.getByLabelText("Quantity 1"));
    await userEvent.type(screen.getByLabelText("Quantity 1"), "2");
    await userEvent.click(screen.getByRole("button", { name: "Add line" }));
    await userEvent.type(screen.getByLabelText("Description 2"), "Discount");
    await userEvent.type(screen.getByLabelText("Unit price 2"), "-20.00");
    await userEvent.type(screen.getByLabelText("Tax % (optional)"), "20");
    expect(screen.getByTestId("draft-subtotal")).toHaveTextContent("€180.00");
    expect(screen.getByTestId("draft-total")).toHaveTextContent("€216.00");

    await userEvent.click(screen.getByRole("button", { name: "Create and send" }));
    await waitFor(() =>
      expect(api.createInvoice).toHaveBeenCalledWith(
        expect.objectContaining({
          customer: "cus_1",
          currency: "EUR",
          lines: [
            { description: "Catering", unit_amount: 10000, quantity: 2 },
            { description: "Discount", unit_amount: -2000, quantity: 1 },
          ],
          collection_method: "send_invoice",
          days_until_due: 14,
          tax_rate_percent: 20,
        }),
        false,
      ),
    );
    await waitFor(() => expect(api.finalizeInvoice).toHaveBeenCalledWith("in_1"));
  });

  it("explains a refused card on the invoice and a bank that wants the customer present", async () => {
    vi.mocked(api.getInvoice).mockResolvedValue(invoice());
    vi.mocked(api.billingNotifications).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.invoicePdf).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
    vi.mocked(api.payInvoice)
      .mockResolvedValueOnce({ invoice: invoice(), paid: false, failure_code: "card_declined", failure_message: "Your card was declined." })
      .mockResolvedValueOnce({ invoice: invoice(), paid: false, failure_code: "authentication_required", failure_message: "x" });
    renderAt("/invoices/in_1");

    await userEvent.click(await screen.findByRole("button", { name: "Charge saved card" }));
    expect(await screen.findByText("Your card was declined.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Charge saved card" }));
    expect(await screen.findByText(/Send them the link to pay instead/)).toBeInTheDocument();
  });

  it("shows the lines of a paid invoice with the proration marked and nothing left to do", async () => {
    vi.mocked(api.getInvoice).mockResolvedValue(
      invoice({
        status: "paid",
        amount_paid: 10000,
        lines: [
          { id: "il_1", description: "Pro", unit_amount: 3000, quantity: 1, amount: 3000, price: "p", proration: false, period_start: null, period_end: null },
          { id: "il_2", description: "Unused time on Basic", unit_amount: 1000, quantity: 1, amount: -500, price: "p", proration: true, period_start: null, period_end: null },
        ],
      }),
    );
    vi.mocked(api.billingNotifications).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.invoicePdf).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
    renderAt("/invoices/in_1");

    expect(await screen.findByTestId("invoice-status")).toHaveTextContent("Paid");
    expect(screen.getByText("proration")).toBeInTheDocument();
    expect(screen.getByTestId("invoice-paid")).toHaveTextContent("€100.00");
    expect(screen.queryByRole("button", { name: "Charge saved card" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Void" })).not.toBeInTheDocument();
  });
});
