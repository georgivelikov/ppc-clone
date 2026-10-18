import type { Customer, PaymentMethod } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CustomerDetailPage } from "./CustomerDetailPage";
import { CustomersPage } from "./CustomersPage";

vi.mock("../../api", () => ({
  api: {
    listCustomers: vi.fn(),
    createCustomer: vi.fn(),
    getCustomer: vi.fn(),
    updateCustomer: vi.fn(),
    deleteCustomer: vi.fn(),
    listPaymentMethods: vi.fn(),
    detachPaymentMethod: vi.fn(),
    createSetupIntent: vi.fn(),
    getSetupIntent: vi.fn(),
    createPayment: vi.fn(),
    listPayments: vi.fn(),
    listSubscriptions: vi.fn(),
    listInvoices: vi.fn(),
  },
}));
vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode: true, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const customer: Customer = {
  id: "cus_1",
  object: "customer",
  livemode: false,
  email: "anna@example.com",
  name: "Anna Petrova",
  phone: null,
  description: null,
  address: null,
  metadata: {},
  default_payment_method: "pm_1",
  created_at: "2026-10-18T10:00:00Z",
};

const card = (id: string, last4: string, isDefault: boolean): PaymentMethod => ({
  id,
  object: "payment_method",
  type: "card",
  customer: "cus_1",
  livemode: false,
  card: { brand: "visa", last4, exp_month: 12, exp_year: 2099, fingerprint: "fp" },
  is_default: isDefault,
  created_at: "2026-10-18T10:00:00Z",
});

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/customers" element={<CustomersPage />} />
          <Route path="/customers/:id" element={<CustomerDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("customers", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.listPayments).mockResolvedValue({ data: [], has_more: false });
    vi.mocked(api.listSubscriptions).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.listInvoices).mockResolvedValue({ object: "list", data: [], has_more: false });
  });

  it("lists customers and searches by what was typed", async () => {
    vi.mocked(api.listCustomers).mockResolvedValue({ object: "list", data: [customer], has_more: false });
    renderAt("/customers");

    expect(await screen.findByText("Anna Petrova")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Search customers"), "anna");
    await waitFor(() => expect(api.listCustomers).toHaveBeenLastCalledWith(expect.objectContaining({ q: "anna" })));
  });

  it("creates a customer and opens it", async () => {
    vi.mocked(api.listCustomers).mockResolvedValue({ object: "list", data: [], has_more: false });
    vi.mocked(api.createCustomer).mockResolvedValue(customer);
    vi.mocked(api.getCustomer).mockResolvedValue(customer);
    vi.mocked(api.listPaymentMethods).mockResolvedValue({ object: "list", data: [] });
    renderAt("/customers");

    await userEvent.click(await screen.findByRole("button", { name: "New customer" }));
    await userEvent.type(screen.getByLabelText("Name"), "Anna Petrova");
    await userEvent.type(screen.getByLabelText("Email"), "not-an-email");
    await userEvent.click(screen.getByRole("button", { name: "Create customer" }));
    expect(await screen.findByText("Enter a valid email")).toBeInTheDocument();
    expect(api.createCustomer).not.toHaveBeenCalled();

    await userEvent.clear(screen.getByLabelText("Email"));
    await userEvent.type(screen.getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: "Create customer" }));

    await waitFor(() =>
      expect(api.createCustomer).toHaveBeenCalledWith({ name: "Anna Petrova", email: "anna@example.com", phone: undefined }, false),
    );
    expect(await screen.findByTestId("customer-name")).toHaveTextContent("Anna Petrova");
  });

  it("shows saved cards, changes the default and removes one", async () => {
    vi.mocked(api.getCustomer).mockResolvedValue(customer);
    vi.mocked(api.listPaymentMethods).mockResolvedValue({ object: "list", data: [card("pm_2", "4444", false), card("pm_1", "4242", true)] });
    vi.mocked(api.updateCustomer).mockResolvedValue(customer);
    vi.mocked(api.detachPaymentMethod).mockResolvedValue(undefined);
    renderAt("/customers/cus_1");

    const cards = await screen.findAllByTestId("saved-card");
    expect(cards.map((c) => c.textContent)).toEqual(["Visa •••• 4444", "Visa •••• 4242"]);
    expect(screen.getByText("Default")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Make default" }));
    await waitFor(() => expect(api.updateCustomer).toHaveBeenCalledWith("cus_1", { default_payment_method: "pm_2" }));

    await userEvent.click(screen.getAllByRole("button", { name: "Remove" })[0]!);
    await waitFor(() => expect(api.detachPaymentMethod).toHaveBeenCalledWith("cus_1", "pm_2"));
  });

  it("offers a link for the customer to save a card and reports when it is done", async () => {
    vi.mocked(api.getCustomer).mockResolvedValue({ ...customer, default_payment_method: null });
    vi.mocked(api.listPaymentMethods).mockResolvedValue({ object: "list", data: [] });
    const setup = {
      id: "seti_1",
      object: "setup_intent" as const,
      livemode: false,
      customer: "cus_1",
      status: "requires_payment_method" as const,
      client_secret: "seti_1_secret_x",
      url: "http://localhost:5174/s/seti_1_secret_x",
      payment_method: null,
      description: null,
      return_url: null,
      failure_code: null,
      failure_message: null,
      created_at: "2026-10-18T10:00:00Z",
    };
    vi.mocked(api.createSetupIntent).mockResolvedValue(setup);
    vi.mocked(api.getSetupIntent)
      .mockResolvedValueOnce(setup)
      .mockResolvedValue({ ...setup, status: "succeeded", payment_method: "pm_1" });
    renderAt("/customers/cus_1");

    await userEvent.click(await screen.findByRole("button", { name: "Add a card" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Create card page" }));

    expect(await within(dialog).findByTestId("setup-url")).toHaveValue("http://localhost:5174/s/seti_1_secret_x");
    expect(api.createSetupIntent).toHaveBeenCalledWith({ customer: "cus_1", merchant_name: "Rose Cafe" }, false);
    expect(await within(dialog).findByText("Card saved.", {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it("charges the default card and explains a bank that wants the customer present", async () => {
    vi.mocked(api.getCustomer).mockResolvedValue(customer);
    vi.mocked(api.listPaymentMethods).mockResolvedValue({ object: "list", data: [card("pm_1", "3184", true)] });
    vi.mocked(api.createPayment).mockResolvedValue({
      id: "pay_1",
      status: "failed",
      failure_code: "authentication_required",
      failure_message: "Your bank requires authentication for this payment.",
    } as never);
    renderAt("/customers/cus_1");

    await userEvent.click(await screen.findByRole("button", { name: "Charge saved card" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/Amount/), "19.00");
    await userEvent.click(within(dialog).getByRole("button", { name: "Charge" }));

    await waitFor(() =>
      expect(api.createPayment).toHaveBeenCalledWith(
        expect.objectContaining({ amount: 1900, customer: "cus_1", off_session: true, currency: "EUR" }),
        false,
      ),
    );
    expect(await within(dialog).findByText(/needs the customer to authenticate/)).toBeInTheDocument();
  });
});
