import type { PaymentMethodSettings } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PaymentMethodsPage } from "./PaymentMethodsPage";

let role = "owner";
vi.mock("../api", () => ({
  api: { getPaymentMethodSettings: vi.fn(), updatePaymentMethodSettings: vi.fn() },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ merchant: { display_name: "Rose Cafe", default_currency: "EUR" }, role }),
}));

const { api } = await import("../api");

const settings: PaymentMethodSettings = {
  object: "payment_method_settings",
  data: [
    { method: "card", enabled: true, locked: true },
    { method: "apple_pay", enabled: true, locked: false },
    { method: "google_pay", enabled: true, locked: false },
    { method: "blik", enabled: true, locked: false },
  ],
};

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/settings/methods"]}>
        <PaymentMethodsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PaymentMethodsPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    role = "owner";
    vi.mocked(api.getPaymentMethodSettings).mockResolvedValue(settings);
  });

  it("lists the methods, with cards locked on", async () => {
    renderPage();
    expect(await screen.findByRole("checkbox", { name: "Accept Cards" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "Accept Cards" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Accept BLIK" })).toBeEnabled();
    expect(screen.getByRole("link", { name: "Instalments" })).toHaveAttribute("href", "/instalments");
  });

  it("switches a method off", async () => {
    vi.mocked(api.updatePaymentMethodSettings).mockResolvedValue({
      ...settings,
      data: settings.data.map((s) => (s.method === "blik" ? { ...s, enabled: false } : s)),
    });
    renderPage();
    await userEvent.click(await screen.findByRole("checkbox", { name: "Accept BLIK" }));

    expect(api.updatePaymentMethodSettings).toHaveBeenCalledWith({ methods: [{ method: "blik", enabled: false }] });
    expect(await within(screen.getByTestId("method-blik")).findByText("Off")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Accept BLIK" })).not.toBeChecked();
  });

  it("only lets owners and admins change them", async () => {
    role = "developer";
    renderPage();
    expect(await screen.findByRole("checkbox", { name: "Accept BLIK" })).toBeDisabled();
    expect(screen.getByText("Only owners and admins can change these.")).toBeInTheDocument();
  });
});
