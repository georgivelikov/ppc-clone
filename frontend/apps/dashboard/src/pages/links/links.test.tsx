import type { PaymentLink } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { buildLinkInput, CreateLinkPage, emptyForm } from "./CreateLinkPage";
import { LinkDetailPage, shareLinks } from "./LinkDetailPage";
import { LinksPage } from "./LinksPage";

vi.mock("../../api", () => ({
  api: {
    listPaymentLinks: vi.fn(),
    createPaymentLink: vi.fn(),
    getPaymentLink: vi.fn(),
    updatePaymentLink: vi.fn(),
    paymentLinkPayments: vi.fn(),
    paymentLinkQr: vi.fn(),
    paymentLinkCard: vi.fn(),
  },
}));

vi.mock("../../auth/AuthProvider", () => ({
  useSession: () => ({ role: "owner", merchant: { display_name: "Rose Cafe", default_currency: "EUR" } }),
}));

let testMode = true;
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

const link = (over: Partial<PaymentLink> = {}): PaymentLink => ({
  id: "plink_1",
  object: "payment_link",
  livemode: false,
  url: "http://localhost:5174/l/AbCdEfGhJk",
  slug: "AbCdEfGhJk",
  status: "active",
  type: "fixed",
  amount: 1500,
  currency: "EUR",
  min_amount: null,
  max_amount: null,
  title: "Table 4",
  description: null,
  image_url: null,
  collect: [],
  quantity_adjustable: false,
  max_uses: null,
  uses_count: 0,
  amount_collected: 0,
  expires_at: null,
  active: true,
  after_completion: "message",
  redirect_url: null,
  success_message: null,
  metadata: {},
  qr_style: {},
  line_items: [],
  created_at: "2026-10-18T10:00:00Z",
  ...over,
});

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/links" element={<LinksPage />} />
          <Route path="/links/new" element={<CreateLinkPage />} />
          <Route path="/links/:id" element={<LinkDetailPage />} />
          <Route path="/payments/:id" element={<p>payment page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  testMode = true;
  URL.createObjectURL = vi.fn(() => "blob:qr");
  URL.revokeObjectURL = vi.fn();
  vi.mocked(api.paymentLinkQr).mockResolvedValue(new Blob(["<svg/>"], { type: "image/svg+xml" }));
});

describe("buildLinkInput", () => {
  const base = { ...emptyForm("EUR"), title: "Table 4", amount: "15.00" };

  it("turns a fixed price into minor units and carries the business name", () => {
    const result = buildLinkInput(base, "Rose Cafe");
    expect(result).toEqual({
      input: { type: "fixed", currency: "EUR", title: "Table 4", merchant_name: "Rose Cafe", amount: 1500, after_completion: "message" },
    });
  });

  it("asks for the missing pieces field by field", () => {
    const result = buildLinkInput({ ...emptyForm("EUR"), maxUses: "0", after: "redirect", redirectUrl: "not a url" }, "x");
    expect(result).toEqual({
      errors: {
        title: "Give the link a name",
        amount: "Enter an amount, like 12.50",
        maxUses: "Enter a whole number, 1 or more",
        redirectUrl: "Enter a full address starting with http:// or https://",
      },
    });
  });

  it("builds a products link from its rows and ignores blank ones", () => {
    const form = {
      ...base,
      type: "products" as const,
      quantityAdjustable: true,
      items: [
        { name: "Soup", price: "6.00", quantity: "1" },
        { name: "Bread", price: "1.50", quantity: "2" },
        { name: "", price: "", quantity: "1" },
      ],
    };
    expect(buildLinkInput(form, "x")).toMatchObject({
      input: {
        type: "products",
        quantity_adjustable: true,
        line_items: [
          { product_name: "Soup", unit_amount: 600, quantity: 1 },
          { product_name: "Bread", unit_amount: 150, quantity: 2 },
        ],
      },
    });
    expect(buildLinkInput({ ...form, items: [{ name: "Soup", price: "", quantity: "1" }] }, "x")).toHaveProperty("errors.items");
  });

  it("checks a customer-chooses range and never sends a quantity for it", () => {
    const form = { ...base, type: "customer_chooses" as const, minAmount: "9", maxAmount: "5", quantityAdjustable: true };
    expect(buildLinkInput(form, "x")).toHaveProperty("errors.minAmount");
    const ok = buildLinkInput({ ...form, minAmount: "5", maxAmount: "50" }, "x");
    expect(ok).toMatchObject({ input: { min_amount: 500, max_amount: 5000 } });
    expect(ok).not.toHaveProperty("input.quantity_adjustable");
  });

  it("only accepts an expiry in the future", () => {
    expect(buildLinkInput({ ...base, expires: "2001-01-01T10:00" }, "x")).toHaveProperty("errors.expires");
    const later = new Date(Date.now() + 86_400_000);
    later.setSeconds(0, 0);
    const local = new Date(later.getTime() - later.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
    expect(buildLinkInput({ ...base, expires: local }, "x")).toHaveProperty("input.expires_at");
  });
});

describe("LinksPage", () => {
  it("lists links with their usage and what they collected", async () => {
    vi.mocked(api.listPaymentLinks).mockResolvedValue({
      object: "list",
      has_more: false,
      data: [link({ uses_count: 2, max_uses: 5, amount_collected: 3000 }), link({ id: "plink_2", title: "Haircut", status: "inactive", active: false })],
    });
    renderAt("/links");

    expect(await screen.findByRole("link", { name: "Table 4" })).toHaveAttribute("href", "/links/plink_1");
    expect(screen.getByText("€30.00")).toBeInTheDocument();
    expect(screen.getByText("/ 5")).toBeInTheDocument();
    expect(screen.getByText("Inactive")).toBeInTheDocument();
    expect(api.listPaymentLinks).toHaveBeenCalledWith(expect.objectContaining({ livemode: false }));
  });

  it("asks for live links when test mode is off, and shows a friendly empty state", async () => {
    testMode = false;
    vi.mocked(api.listPaymentLinks).mockResolvedValue({ object: "list", has_more: false, data: [] });
    renderAt("/links");
    expect(await screen.findByText("No payment links yet")).toBeInTheDocument();
    expect(api.listPaymentLinks).toHaveBeenCalledWith(expect.objectContaining({ livemode: true }));
  });
});

describe("CreateLinkPage", () => {
  it("shows a live preview and creates the link, then opens it", async () => {
    vi.mocked(api.createPaymentLink).mockResolvedValue(link());
    vi.mocked(api.getPaymentLink).mockResolvedValue(link());
    vi.mocked(api.paymentLinkPayments).mockResolvedValue({ object: "list", data: [] });
    renderAt("/links/new");

    await userEvent.type(screen.getByLabelText("Name"), "Table 4");
    await userEvent.type(screen.getByLabelText("Amount"), "15");
    expect(screen.getByTestId("preview-amount")).toHaveTextContent("€15.00");
    await userEvent.click(screen.getByLabelText("Email"));
    await userEvent.type(screen.getByLabelText("Limit number of payments (optional)"), "1");
    await userEvent.click(screen.getByRole("button", { name: "Create link" }));

    await waitFor(() => expect(api.createPaymentLink).toHaveBeenCalled());
    expect(api.createPaymentLink).toHaveBeenCalledWith(
      {
        type: "fixed",
        currency: "EUR",
        title: "Table 4",
        merchant_name: "Rose Cafe",
        amount: 1500,
        collect: ["email"],
        max_uses: 1,
        after_completion: "message",
      },
      false,
    );
    expect(await screen.findByRole("heading", { name: /Table 4/ })).toBeInTheDocument();
  });

  it("does not call the API while the form has problems", async () => {
    renderAt("/links/new");
    await userEvent.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByText("Give the link a name")).toBeInTheDocument();
    expect(screen.getByText("Enter an amount, like 12.50")).toBeInTheDocument();
    expect(api.createPaymentLink).not.toHaveBeenCalled();
  });

  it("switches the price fields with the type and lets rows be added", async () => {
    renderAt("/links/new");
    await userEvent.click(screen.getByLabelText("Products"));
    expect(screen.queryByLabelText("Amount")).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Item 1 name"), "Soup");
    await userEvent.type(screen.getByLabelText("Item 1 price"), "6");
    await userEvent.click(screen.getByRole("button", { name: "Add item" }));
    expect(screen.getByLabelText("Item 2 name")).toBeInTheDocument();
    expect(screen.getByTestId("preview-amount")).toHaveTextContent("€6.00");
    await userEvent.click(screen.getByLabelText("Customer chooses"));
    expect(screen.getByTestId("preview-amount")).toHaveTextContent("Customer enters the amount");
  });

  it("shows the server's message when the link is refused", async () => {
    const { ApiError } = await import("@cutpay/api-client");
    vi.mocked(api.createPaymentLink).mockRejectedValue(new ApiError(400, "invalid_request_error", "unsupported_currency", "Currency is not supported"));
    renderAt("/links/new");
    await userEvent.type(screen.getByLabelText("Name"), "X");
    await userEvent.type(screen.getByLabelText("Amount"), "5");
    await userEvent.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByText("Currency is not supported")).toBeInTheDocument();
  });
});

describe("LinkDetailPage", () => {
  beforeEach(() => {
    vi.mocked(api.getPaymentLink).mockResolvedValue(link({ uses_count: 1, amount_collected: 1500 }));
    vi.mocked(api.paymentLinkPayments).mockResolvedValue({
      object: "list",
      data: [{ payment_id: "pay_9", reservation_id: "lres_1", status: "used", amount: 1500, created_at: "2026-10-18T10:05:00Z" }],
    });
  });

  it("shows the address, the QR code and the payments made", async () => {
    renderAt("/links/plink_1");
    expect(await screen.findByTestId("link-url")).toHaveTextContent("http://localhost:5174/l/AbCdEfGhJk");
    expect(await screen.findByAltText("QR code for Table 4")).toHaveAttribute("src", "blob:qr");
    expect(await screen.findByRole("link", { name: "pay_9" })).toHaveAttribute("href", "/payments/pay_9");
    expect(api.paymentLinkQr).toHaveBeenCalledWith("plink_1", { format: "svg", fg: "#000000", bg: "#ffffff" });
  });

  it("redraws the preview and offers to save when the colours change", async () => {
    vi.mocked(api.updatePaymentLink).mockResolvedValue(link({ qr_style: { fg: "#123456", bg: "#ffffff" } }));
    renderAt("/links/plink_1");
    await screen.findByAltText("QR code for Table 4");
    expect(screen.queryByRole("button", { name: "Save colours" })).not.toBeInTheDocument();

    const picker = screen.getByLabelText("Code colour");
    picker.focus();
    // React tracks the value of colour inputs; fire the change the way the browser does.
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
    setter.call(picker, "#123456");
    picker.dispatchEvent(new Event("input", { bubbles: true }));

    await waitFor(() => expect(api.paymentLinkQr).toHaveBeenCalledWith("plink_1", { format: "svg", fg: "#123456", bg: "#ffffff" }));
    await userEvent.click(await screen.findByRole("button", { name: "Save colours" }));
    expect(api.updatePaymentLink).toHaveBeenCalledWith("plink_1", { qr_style: { fg: "#123456", bg: "#ffffff" } });
  });

  it("deactivates the link", async () => {
    vi.mocked(api.updatePaymentLink).mockResolvedValue(link({ active: false, status: "inactive" }));
    renderAt("/links/plink_1");
    await userEvent.click(await screen.findByRole("button", { name: "Deactivate" }));
    expect(api.updatePaymentLink).toHaveBeenCalledWith("plink_1", { active: false });
    expect(await screen.findByRole("button", { name: "Activate" })).toBeInTheDocument();
  });

  it("copies the address", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    renderAt("/links/plink_1");
    await userEvent.click(await screen.findByRole("button", { name: "Copy" }));
    expect(writeText).toHaveBeenCalledWith("http://localhost:5174/l/AbCdEfGhJk");
    expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("says so when the link does not exist", async () => {
    const { ApiError } = await import("@cutpay/api-client");
    vi.mocked(api.getPaymentLink).mockRejectedValue(new ApiError(404, "invalid_request_error", "link_not_found", "No such payment link"));
    renderAt("/links/nope");
    expect(await screen.findByText("That payment link does not exist.")).toBeInTheDocument();
  });
});

describe("shareLinks", () => {
  it("builds email, WhatsApp and Viber addresses with the title and link encoded", () => {
    const links = shareLinks({ title: "Table 4 & drinks", url: "http://localhost:5174/l/AbCdEfGhJk" });
    expect(links.email).toBe("mailto:?subject=Table%204%20%26%20drinks&body=Pay%20here%3A%20http%3A%2F%2Flocalhost%3A5174%2Fl%2FAbCdEfGhJk");
    expect(links.whatsapp).toBe("https://wa.me/?text=Table%204%20%26%20drinks%3A%20http%3A%2F%2Flocalhost%3A5174%2Fl%2FAbCdEfGhJk");
    expect(links.viber).toBe("viber://forward?text=Table%204%20%26%20drinks%3A%20http%3A%2F%2Flocalhost%3A5174%2Fl%2FAbCdEfGhJk");
  });
});
