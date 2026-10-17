import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CheckoutApiError, type PublicLink } from "./api";
import { LinkPage, LinkThanksPage, totalFor } from "./LinkPage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getLink: vi.fn(),
  createLinkSession: vi.fn(),
}));

const api = await import("./api");

const fixed: PublicLink = {
  slug: "AbCdEfGhJk",
  livemode: false,
  merchant_name: "Rose Cafe",
  type: "fixed",
  amount: 1500,
  currency: "EUR",
  min_amount: null,
  max_amount: null,
  title: "Table 4",
  description: "Lunch menu",
  image_url: null,
  collect: [],
  quantity_adjustable: false,
  line_items: [],
  available: true,
  unavailable_reason: null,
  after_completion: "message",
  success_message: null,
};

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/l/:slug" element={<LinkPage />} />
          <Route path="/l/:slug/thanks" element={<LinkThanksPage />} />
          <Route path="/c/:secret" element={<p>checkout for secret</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("totalFor", () => {
  it("multiplies fixed and product links by the quantity only when it can be adjusted", () => {
    expect(totalFor({ ...fixed, quantity_adjustable: true }, "", 3)).toBe(4500);
    expect(totalFor(fixed, "", 3)).toBe(1500);
    const products = { ...fixed, type: "products" as const, amount: null, line_items: [
      { product_name: "Soup", unit_amount: 600, quantity: 1 },
      { product_name: "Bread", unit_amount: 150, quantity: 2 },
    ] };
    expect(totalFor(products, "", 1)).toBe(900);
  });

  it("reads what the customer typed, or nothing while it is not an amount", () => {
    const chooses = { ...fixed, type: "customer_chooses" as const, amount: null };
    expect(totalFor(chooses, "12,50", 1)).toBe(1250);
    expect(totalFor(chooses, "abc", 1)).toBeNull();
  });
});

describe("LinkPage", () => {
  beforeEach(() => {
    vi.mocked(api.getLink).mockReset();
    vi.mocked(api.createLinkSession).mockReset();
  });

  it("shows what is being paid for and goes to checkout when the customer continues", async () => {
    vi.mocked(api.getLink).mockResolvedValue(fixed);
    vi.mocked(api.createLinkSession).mockResolvedValue({ payment_id: "pay_1", client_secret: "pay_1_secret_x", url: "u" });
    renderAt("/l/AbCdEfGhJk");

    expect(await screen.findByRole("heading", { name: "Table 4" })).toBeInTheDocument();
    expect(screen.getByText("Rose Cafe")).toBeInTheDocument();
    expect(screen.getByTestId("amount")).toHaveTextContent("€15.00");
    expect(screen.getByText(/test mode/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));

    expect(await screen.findByText("checkout for secret")).toBeInTheDocument();
    expect(api.createLinkSession).toHaveBeenCalledWith("AbCdEfGhJk", {});
  });

  it("asks only for what the merchant collects and checks it before starting a payment", async () => {
    vi.mocked(api.getLink).mockResolvedValue({ ...fixed, collect: ["email", "name"] });
    renderAt("/l/AbCdEfGhJk");
    await screen.findByRole("heading", { name: "Table 4" });
    expect(screen.queryByLabelText("Phone")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));
    expect(await screen.findByText("Enter your name")).toBeInTheDocument();
    expect(screen.getByText("Enter a valid email")).toBeInTheDocument();
    expect(api.createLinkSession).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText("Full name"), "Ana Petrova");
    await userEvent.type(screen.getByLabelText("Email"), "ana@example.com");
    vi.mocked(api.createLinkSession).mockResolvedValue({ payment_id: "pay_1", client_secret: "pay_1_secret_x", url: "u" });
    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));
    await waitFor(() =>
      expect(api.createLinkSession).toHaveBeenCalledWith("AbCdEfGhJk", { email: "ana@example.com", name: "Ana Petrova" }),
    );
  });

  it("lets the customer choose the amount within the merchant's range", async () => {
    vi.mocked(api.getLink).mockResolvedValue({
      ...fixed,
      type: "customer_chooses",
      amount: null,
      min_amount: 500,
      max_amount: 5000,
      title: "Tip",
    });
    renderAt("/l/AbCdEfGhJk");
    await screen.findByRole("heading", { name: "Tip" });

    await userEvent.type(screen.getByLabelText("Amount (EUR)"), "2");
    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));
    expect(await screen.findByText("The minimum is €5.00")).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText("Amount (EUR)"));
    await userEvent.type(screen.getByLabelText("Amount (EUR)"), "25.50");
    expect(screen.getByTestId("total")).toHaveTextContent("€25.50");
    vi.mocked(api.createLinkSession).mockResolvedValue({ payment_id: "pay_1", client_secret: "pay_1_secret_x", url: "u" });
    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));
    await waitFor(() => expect(api.createLinkSession).toHaveBeenCalledWith("AbCdEfGhJk", { amount: 2550 }));
  });

  it("updates the total when the quantity changes and sends it", async () => {
    vi.mocked(api.getLink).mockResolvedValue({ ...fixed, quantity_adjustable: true });
    renderAt("/l/AbCdEfGhJk");
    await screen.findByRole("heading", { name: "Table 4" });

    await userEvent.click(screen.getByRole("button", { name: "More" }));
    await userEvent.click(screen.getByRole("button", { name: "More" }));
    expect(screen.getByTestId("total")).toHaveTextContent("€45.00");
    await userEvent.click(screen.getByRole("button", { name: "Fewer" }));
    expect(screen.getByTestId("total")).toHaveTextContent("€30.00");

    vi.mocked(api.createLinkSession).mockResolvedValue({ payment_id: "pay_1", client_secret: "pay_1_secret_x", url: "u" });
    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));
    await waitFor(() => expect(api.createLinkSession).toHaveBeenCalledWith("AbCdEfGhJk", { quantity: 2 }));
  });

  it("tells the customer when someone else took the last use first", async () => {
    vi.mocked(api.getLink).mockResolvedValue({ ...fixed });
    vi.mocked(api.createLinkSession).mockRejectedValue(
      new CheckoutApiError(409, "link_unavailable", "This payment link is no longer available"),
    );
    renderAt("/l/AbCdEfGhJk");
    await screen.findByRole("heading", { name: "Table 4" });

    await userEvent.click(screen.getByRole("button", { name: "Continue to payment" }));
    expect(await screen.findByText("This payment link is no longer available.")).toBeInTheDocument();
    expect(screen.queryByText("checkout for secret")).not.toBeInTheDocument();
  });

  it.each([
    ["sold_out", "This payment link is no longer available."],
    ["expired", "This payment link has expired."],
    ["inactive", "This payment link is no longer active."],
  ] as const)("explains an unavailable link (%s) and offers no way to pay", async (reason, message) => {
    vi.mocked(api.getLink).mockResolvedValue({ ...fixed, available: false, unavailable_reason: reason });
    renderAt("/l/AbCdEfGhJk");
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Continue to payment" })).not.toBeInTheDocument();
  });

  it("says so when the link does not exist", async () => {
    vi.mocked(api.getLink).mockRejectedValue(new CheckoutApiError(404, "link_not_found", "nope"));
    renderAt("/l/nothing");
    expect(await screen.findByRole("heading", { name: "Payment link not found" })).toBeInTheDocument();
  });

  it("shows the merchant's thank-you message after payment", async () => {
    vi.mocked(api.getLink).mockResolvedValue({ ...fixed, success_message: "See you at table 4!" });
    renderAt("/l/AbCdEfGhJk/thanks");
    expect(await screen.findByText("See you at table 4!")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Thank you!" })).toBeInTheDocument();
  });
});
