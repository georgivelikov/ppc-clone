import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";

// Redoc is large and needs a real browser layout; the e2e test covers it. Here it is a stand-in.
vi.mock("./pages/docs/ApiReferencePage", () => ({ ApiReferencePage: () => <p>API reference stand-in</p> }));

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("navigation", () => {
  it("has the main sections and sends sign-up to the dashboard", () => {
    renderAt("/");
    const nav = screen.getByRole("navigation", { name: "Main" });
    for (const name of ["Products", "Pricing", "Business types", "Docs", "FAQ"]) {
      expect(within(nav).getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Start for free" })).toHaveAttribute("href", "http://localhost:5173/signup");
  });

  it("shows a not-found page with a way back", () => {
    renderAt("/nothing-here");
    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  });
});

describe("marketing pages", () => {
  it("home shows the SDK snippet and the main promise", () => {
    renderAt("/");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Get paid by card");
    expect(screen.getByText(/elements.create\("card"\)/)).toBeInTheDocument();
  });

  it("products separates what exists from what is coming", () => {
    renderAt("/products");
    expect(screen.getAllByText("Available")).toHaveLength(2);
    expect(screen.getAllByText("Coming soon")).toHaveLength(3);
    for (const name of ["Checkout", "Billing & payouts", "Links & QR", "Virtual POS", "Buy now, pay later"]) {
      expect(screen.getByRole("heading", { name })).toBeInTheDocument();
    }
  });

  it("business types lists who it is for", () => {
    renderAt("/business-types");
    expect(screen.getByRole("heading", { name: "Online shops" })).toBeInTheDocument();
  });

  it("faq answers open and close", async () => {
    renderAt("/faq");
    const question = screen.getByText("Is this real money?");
    const details = question.closest("details")!;
    expect(details.open).toBe(false);
    await userEvent.click(question);
    expect(details.open).toBe(true);
    expect(within(details).getByText(/never type a real card number/)).toBeInTheDocument();
  });
});

describe("pricing", () => {
  it("shows both fees and the payout cost", () => {
    renderAt("/pricing");
    expect(screen.getByText("1.29% + €0.10")).toBeInTheDocument();
    expect(screen.getAllByText("2.69% + €0.10").length).toBeGreaterThan(0);
    expect(screen.getByText(/costs 1%/)).toBeInTheDocument();
  });

  it("the calculator reproduces the example from the docs: €100 pays €1.39", () => {
    renderAt("/pricing");
    expect(screen.getByTestId("calc-fee")).toHaveTextContent("−€1.39");
    expect(screen.getByTestId("calc-net")).toHaveTextContent("€98.61");
  });

  it("the calculator follows the amount and the card, and complains about nonsense", async () => {
    renderAt("/pricing");
    await userEvent.selectOptions(screen.getByLabelText("Card"), "other");
    expect(screen.getByTestId("calc-fee")).toHaveTextContent("−€2.79");

    const amount = screen.getByLabelText("Payment amount (EUR)");
    await userEvent.clear(amount);
    await userEvent.type(amount, "25.00");
    expect(screen.getByTestId("calc-fee")).toHaveTextContent("−€0.77"); // 2.69% of 25.00 = 0.6725 -> 0.67, plus 0.10
    expect(screen.getByTestId("calc-net")).toHaveTextContent("€24.23");

    await userEvent.clear(amount);
    await userEvent.type(amount, "abc");
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter an amount");
  });
});

describe("docs", () => {
  it("getting started walks through keys, a payment, the card field and webhooks", () => {
    renderAt("/docs");
    expect(screen.getByRole("heading", { name: "Getting started" })).toBeInTheDocument();
    for (const heading of [/Create an account and a key/, /Create a payment/, /Collect the card/, /Find out the payment succeeded/]) {
      expect(screen.getByRole("heading", { name: heading })).toBeInTheDocument();
    }
    expect(screen.getAllByText(/Authorization: Bearer sk_test_/).length).toBeGreaterThan(0);
  });

  it("test cards lists the outcomes and the fee tiers", () => {
    renderAt("/docs/test-cards");
    // The approved card heads the outcomes table and also starts the fee tiers.
    expect(screen.getAllByText("4242 4242 4242 4242", { selector: "td" })).toHaveLength(2);
    expect(screen.getByText("3-D Secure")).toBeInTheDocument();
    expect(screen.getByText(/Enter 1234/)).toBeInTheDocument();
  });

  it("webhooks guide shows the signature scheme and the retry schedule", () => {
    renderAt("/docs/webhooks");
    expect(screen.getByRole("heading", { name: "Check the signature" })).toBeInTheDocument();
    expect(screen.getByText(/1 minute, 5 minutes, 30 minutes/)).toBeInTheDocument();
    expect(screen.getByText("payout.paid")).toBeInTheDocument();
  });

  it("api reference is part of the docs navigation", async () => {
    renderAt("/docs/api");
    expect(await screen.findByText("API reference stand-in")).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "Docs" })).getByRole("link", { name: "API reference" })).toBeInTheDocument();
  });

  it("code samples can be copied", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    renderAt("/docs");
    await userEvent.click(screen.getAllByRole("button", { name: /^Copy/ })[0]!);
    expect(writeText).toHaveBeenCalledOnce();
    expect(await screen.findByText("Copied")).toBeInTheDocument();
  });
});
