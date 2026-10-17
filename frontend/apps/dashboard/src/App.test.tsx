import { ApiError, type AuthResponse } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthProvider";

vi.mock("./api", () => ({
  api: {
    restoreSession: vi.fn(),
    login: vi.fn(),
    signup: vi.fn(),
    logout: vi.fn(),
    updateMerchant: vi.fn(),
    paymentSummary: vi.fn().mockResolvedValue({ since: "2026-01-01T00:00:00Z", today: [] }),
  },
  onSessionExpired: vi.fn(),
}));

const { api } = await import("./api");

const session: AuthResponse = {
  access_token: "tok",
  token_type: "Bearer",
  expires_in: 900,
  user: { id: "usr_1", email: "ivan@example.com", full_name: "Ivan Petrov" },
  merchant: {
    id: "mer_1",
    display_name: "Rose Cafe",
    legal_name: "Rose Cafe",
    country: "BG",
    default_currency: "EUR",
    status: "pending_kyc",
  },
  role: "owner",
};

function renderApp(initialPath: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("dashboard", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.paymentSummary).mockResolvedValue({ since: "2026-01-01T00:00:00Z", today: [] });
    localStorage.clear();
  });

  it("sends visitors without a session to the login page", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(null);

    renderApp("/");

    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  });

  it("restores the session after a reload and shows the merchant", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(session);

    renderApp("/");

    expect(await screen.findByTestId("merchant-name")).toHaveTextContent("Rose Cafe");
    expect(screen.getByRole("heading", { name: "Welcome, Ivan" })).toBeInTheDocument();
  });

  it("validates the login form before calling the API", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(null);
    renderApp("/login");

    await userEvent.click(await screen.findByRole("button", { name: "Sign in" }));

    expect(await screen.findByText("Enter your email")).toBeInTheDocument();
    expect(screen.getByText("Enter your password")).toBeInTheDocument();
    expect(api.login).not.toHaveBeenCalled();
  });

  it("shows the server's message for wrong credentials", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(null);
    vi.mocked(api.login).mockRejectedValue(
      new ApiError(401, "authentication_error", "invalid_credentials", "Incorrect email or password"),
    );
    renderApp("/login");

    await userEvent.type(await screen.findByLabelText("Email"), "ivan@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "wrong-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Incorrect email or password");
  });

  it("signs in and lands on the dashboard", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(null);
    vi.mocked(api.login).mockResolvedValue(session);
    renderApp("/login");

    await userEvent.type(await screen.findByLabelText("Email"), "ivan@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "correct-horse-battery");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(screen.getByTestId("merchant-name")).toHaveTextContent("Rose Cafe"));
    expect(api.login).toHaveBeenCalledWith({ email: "ivan@example.com", password: "correct-horse-battery" });
  });

  it("defaults to test mode and remembers the switch", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(session);
    renderApp("/");

    const toggle = await screen.findByRole("switch", { name: "Test mode" });
    expect(toggle).toBeChecked();
    expect(screen.getByText(/viewing test data/)).toBeInTheDocument();

    await userEvent.click(toggle);

    expect(screen.queryByText(/viewing test data/)).not.toBeInTheDocument();
    expect(localStorage.getItem("cutpay.testMode")).toBe("false");
  });

  it("puts a server-side field error under the right input on sign-up", async () => {
    vi.mocked(api.restoreSession).mockResolvedValue(null);
    vi.mocked(api.signup).mockRejectedValue(
      new ApiError(409, "invalid_request_error", "email_taken", "An account with this email already exists", "email"),
    );
    renderApp("/signup");

    await userEvent.type(await screen.findByLabelText("Your name"), "Ivan Petrov");
    await userEvent.type(screen.getByLabelText("Business name"), "Rose Cafe");
    await userEvent.type(screen.getByLabelText("Work email"), "ivan@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "correct-horse-battery");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("An account with this email already exists")).toBeInTheDocument();
  });
});
