import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { PublicInvoice } from "./api";
import { InvoicePage } from "./InvoicePage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getInvoice: vi.fn(),
  startInvoicePayment: vi.fn(),
}));

const api = await import("./api");

const open: PublicInvoice = {
  number: "INV-0007",
  status: "open",
  merchant_name: "Coffee Club",
  customer_name: "Anna Petrova",
  customer_email: "anna@example.com",
  currency: "EUR",
  subtotal: 19000,
  tax: 3800,
  credit_applied: 0,
  total: 22800,
  amount_paid: 0,
  description: "Catering for the launch",
  due_date: "2026-11-01T00:00:00Z",
  paid_at: null,
  lines: [
    { id: "il_1", description: "Catering", quantity: 2, unit_amount: 10000, amount: 20000, proration: false },
    { id: "il_2", description: "Discount", quantity: 1, unit_amount: -1000, amount: -1000, proration: false },
  ],
  payable: true,
};

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/i/:token" element={<InvoicePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("hosted invoice", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("shows what is owed and sends the customer to the hosted checkout to pay", async () => {
    vi.mocked(api.getInvoice).mockResolvedValue(open);
    vi.mocked(api.startInvoicePayment).mockResolvedValue({ url: "http://localhost:5174/c/pay_1_secret" });
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    renderAt("/i/tok_abc");

    expect(await screen.findByTestId("invoice-number")).toHaveTextContent("INV-0007");
    expect(screen.getByTestId("invoice-total")).toHaveTextContent("€228.00");
    expect(screen.getByText("Billed to Anna Petrova")).toBeInTheDocument();
    expect(screen.getByText("Awaiting payment")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /^Pay €228\.00/ }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("http://localhost:5174/c/pay_1_secret"));
    expect(api.startInvoicePayment).toHaveBeenCalledWith("tok_abc");
    vi.unstubAllGlobals();
  });

  it("offers no payment for a paid invoice and thanks the customer", async () => {
    vi.mocked(api.getInvoice).mockResolvedValue({ ...open, status: "paid", payable: false, paid_at: "2026-10-18T10:00:00Z", amount_paid: 22800 });
    renderAt("/i/tok_abc");

    expect(await screen.findByText(/Thank you/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Pay/ })).not.toBeInTheDocument();
  });

  it("tells a customer who just came back from paying that it is being confirmed", async () => {
    vi.mocked(api.getInvoice).mockResolvedValue(open);
    renderAt("/i/tok_abc?returned=1");

    expect(await screen.findByText("Confirming your payment…")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Pay/ })).not.toBeInTheDocument();
  });

  it("says so when the invoice was canceled or the link is wrong", async () => {
    vi.mocked(api.getInvoice).mockResolvedValue({ ...open, status: "void", payable: false });
    const view = renderAt("/i/tok_abc");
    expect(await screen.findByText(/You do not need to pay it/)).toBeInTheDocument();
    view.unmount();

    vi.mocked(api.getInvoice).mockRejectedValue(new api.CheckoutApiError(404, "invoice_not_found", "No such invoice"));
    renderAt("/i/nope");
    expect(await screen.findByText("This invoice link is not valid.")).toBeInTheDocument();
  });
});
