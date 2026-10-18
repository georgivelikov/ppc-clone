import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SetupSession } from "./api";
import { SetupPage } from "./SetupPage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSetupSession: vi.fn(),
  tokenize: vi.fn(),
  confirmSetup: vi.fn(),
  completeThreeDs: vi.fn(),
}));

const api = await import("./api");

const base: SetupSession = {
  id: "seti_1",
  status: "requires_payment_method",
  merchant_name: "Rose Cafe",
  description: "Monthly coffee club",
  return_url: null,
  failure_code: null,
  failure_message: null,
  next_action: null,
};

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/s/seti_1_secret_x"]}>
        <Routes>
          <Route path="/s/:secret" element={<SetupPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function fillCard(number: string) {
  await userEvent.type(screen.getByLabelText("Card number"), number);
  await userEvent.type(screen.getByLabelText("Expiry"), "1299");
  await userEvent.type(screen.getByLabelText("CVC"), "123");
}

describe("saving a card", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("says the card is not charged and sends only the vault token to the payments service", async () => {
    vi.mocked(api.getSetupSession).mockResolvedValue(base);
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirmSetup).mockResolvedValue({ ...base, status: "succeeded" });
    renderPage();

    expect(await screen.findByText(/You will not be charged now/)).toBeInTheDocument();
    expect(screen.getByText(/Rose Cafe: Monthly coffee club/)).toBeInTheDocument();
    await fillCard("4242424242424242");
    await userEvent.click(screen.getByRole("button", { name: "Save card" }));

    expect(await screen.findByRole("heading", { name: "Card saved" })).toBeInTheDocument();
    expect(api.tokenize).toHaveBeenCalledWith(expect.objectContaining({ number: "4242 4242 4242 4242" }));
    expect(api.confirmSetup).toHaveBeenCalledWith("seti_1_secret_x", "tok_1");
  });

  it("asks the customer to confirm with the bank when 3-D Secure is needed", async () => {
    vi.mocked(api.getSetupSession).mockResolvedValue(base);
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirmSetup).mockResolvedValue({
      ...base,
      status: "requires_action",
      next_action: { type: "three_ds", challenge_id: "ver_1" },
    });
    renderPage();

    await screen.findByLabelText("Card number");
    await fillCard("4000000000003220");
    await userEvent.click(screen.getByRole("button", { name: "Save card" }));

    expect(await screen.findByRole("heading", { name: "Confirm with your bank" })).toBeInTheDocument();
  });

  it("shows why the card was refused", async () => {
    vi.mocked(api.getSetupSession).mockResolvedValue(base);
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirmSetup).mockResolvedValue({
      ...base,
      status: "failed",
      failure_code: "card_declined",
      failure_message: "Your card was declined.",
    });
    renderPage();

    await screen.findByLabelText("Card number");
    await fillCard("4000000000000002");
    await userEvent.click(screen.getByRole("button", { name: "Save card" }));

    expect(await screen.findByRole("heading", { name: "Card not saved" })).toBeInTheDocument();
    expect(screen.getByText("Your card was declined.")).toBeInTheDocument();
  });

  it("does not take a card on a link that was already used", async () => {
    vi.mocked(api.getSetupSession).mockResolvedValue({ ...base, status: "succeeded" });
    renderPage();

    expect(await screen.findByRole("heading", { name: "Card saved" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByLabelText("Card number")).not.toBeInTheDocument());
  });
});
