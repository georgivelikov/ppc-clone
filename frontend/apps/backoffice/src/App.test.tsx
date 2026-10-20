import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { AuthProvider } from "./auth";

function renderApp(path = "/") {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const json = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));

describe("the back-office", () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it("asks staff to sign in and says plainly when the password is wrong", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(401, { error: { code: "invalid_credentials", message: "Incorrect email or password" } })));
    renderApp();
    await userEvent.type(screen.getByLabelText("Email"), "admin@cutpay.test");
    await userEvent.type(screen.getByLabelText("Password"), "nope-nope-nope");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Incorrect email or password");
  });

  it("signs in, lists the review queue and signs out again", async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url === "/auth/staff/login") {
        return json(200, { access_token: "tok", id: "stf_1", email: "reviewer@cutpay.test", full_name: "Rita Reviewer", role: "reviewer" });
      }
      return json(200, {
        data: [{ merchant_id: "mer_1", merchant_name: "Rose Cafe", status: "in_review", risk_score: 60, manual_review: true, submitted_at: "2026-10-20T10:00:00Z" }],
      });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderApp();
    await userEvent.type(screen.getByLabelText("Email"), "reviewer@cutpay.test");
    await userEvent.type(screen.getByLabelText("Password"), "staff-password-123");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByTestId("staff-name")).toHaveTextContent("Rita Reviewer");
    expect(await screen.findByTestId("queue-row")).toHaveTextContent("needs a person");
    // The staff token goes with every call.
    const call = fetchMock.mock.calls.find(([u]) => String(u).startsWith("/v1/staff/applications")) as unknown as [string, RequestInit];
    expect((call[1].headers as Record<string, string>).Authorization).toBe("Bearer tok");

    await userEvent.click(screen.getByRole("button", { name: /Sign out/ }));
    expect(screen.getByRole("form", { name: "Staff sign-in" })).toBeInTheDocument();
    expect(sessionStorage.getItem("cutpay.staff.token")).toBeNull();
  });
});
