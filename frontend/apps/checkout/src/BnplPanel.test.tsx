import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Session } from "./api";
import { CheckoutApiError } from "./api";
import { validate } from "./BnplPanel";
import { costText, fieldsFor, intervalText, planText } from "./bnpl";
import type { BnplApplicationView, BnplOffer, BnplOffers } from "./bnplApi";
import { CheckoutPage } from "./CheckoutPage";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSession: vi.fn(),
  tokenize: vi.fn(),
  confirm: vi.fn(),
}));
vi.mock("./bnplApi", () => ({
  getBnplOffers: vi.fn(),
  applyBnpl: vi.fn(),
  applyBnplBest: vi.fn(),
  getBnplApplication: vi.fn(),
  cancelBnplApplication: vi.fn(),
}));

const api = await import("./api");
const bnpl = await import("./bnplApi");

const session: Session = {
  id: "pay_1",
  status: "requires_payment_method",
  amount: 30000,
  currency: "EUR",
  description: "Sofa",
  merchant_name: "Rose Cafe",
  return_url: null,
  failure_code: null,
  failure_message: null,
  next_action: null,
};

function offer(partial: Partial<BnplOffer> & Pick<BnplOffer, "provider" | "provider_name" | "plan">): BnplOffer {
  return {
    instalments: 3,
    instalment_amounts: [10000, 10000, 10000],
    interval_days: 14,
    apr: 0,
    total_payable: 30000,
    customer_cost: 0,
    required_fields: ["email"],
    ...partial,
  };
}

const splitnow = offer({ provider: "splitnow", provider_name: "SplitNow", plan: "splitnow_3" });
const lendo = offer({
  provider: "lendo",
  provider_name: "Lendo",
  plan: "lendo_12",
  instalments: 12,
  instalment_amounts: Array(12).fill(2638),
  interval_days: 30,
  apr: 9.9,
  total_payable: 31656,
  customer_cost: 1656,
  required_fields: ["email", "birthdate"],
});
const kreditko = offer({
  provider: "kreditko",
  provider_name: "KreditKo",
  plan: "kreditko_12",
  instalments: 12,
  instalment_amounts: Array(12).fill(2680),
  interval_days: 30,
  apr: 12.9,
  total_payable: 32160,
  customer_cost: 2160,
  required_fields: ["email", "national_id", "monthly_income"],
});
const payLater = offer({
  provider: "paylatereu",
  provider_name: "PayLaterEU",
  plan: "paylatereu_30",
  instalments: 1,
  instalment_amounts: [30000],
  interval_days: 30,
});

const offers: BnplOffers = {
  object: "bnpl_offers",
  payment: "pay_1",
  amount: 30000,
  currency: "EUR",
  country: "BG",
  routing_mode: "customer_choice",
  offers: [splitnow, lendo, kreditko, payLater],
  recommended: { provider: "splitnow", plan: "splitnow_3" },
  unavailable: [],
};

function application(partial: Partial<BnplApplicationView>): BnplApplicationView {
  return {
    object: "bnpl_application",
    id: "bnpl_1",
    status: "approved",
    provider: "splitnow",
    provider_name: "SplitNow",
    plan: "splitnow_3",
    instalments: 3,
    instalment_amounts: [10000, 10000, 10000],
    interval_days: 14,
    apr: 0,
    total_payable: 30000,
    awaiting_decision: false,
    message: "Your application was approved.",
    ...partial,
  };
}

function renderCheckout() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/c/pay_1_secret_x"]}>
        <Routes>
          <Route path="/c/:secret" element={<CheckoutPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The form of the instalments tab; the card form is also in the page, with fields of the same names. */
const instalments = () => within(screen.getByRole("form", { name: "Pay in instalments" }));

async function openInstalments() {
  await userEvent.click(await screen.findByRole("tab", { name: "Pay in instalments" }));
}

describe("the wording of a plan", () => {
  it("says how often the instalments fall due", () => {
    expect(intervalText(7)).toBe("every week");
    expect(intervalText(14)).toBe("every 2 weeks");
    expect(intervalText(30)).toBe("every month");
    expect(intervalText(90)).toBe("every 3 months");
    expect(intervalText(10)).toBe("every 10 days");
  });

  it("describes what is paid and what it costs", () => {
    expect(planText(splitnow, "EUR")).toBe("3 payments of €100.00 every 2 weeks");
    expect(planText(payLater, "EUR")).toBe("€300.00 in 30 days");
    expect(planText({ instalments: 3, instalment_amounts: [3334, 3333, 3333], interval_days: 14 }, "EUR")).toBe(
      "3 payments of about €33.34 every 2 weeks",
    );
    expect(costText(splitnow, "EUR")).toBe("No interest · total €300.00");
    expect(costText(lendo, "EUR")).toBe("€16.56 interest (9.9% APR) · total €316.56");
  });

  it("asks for the fields the offers need, in a fixed order", () => {
    expect(fieldsFor([splitnow])).toEqual(["email"]);
    expect(fieldsFor([kreditko, lendo])).toEqual(["email", "birthdate", "national_id", "monthly_income"]);
  });
});

describe("checking what the customer typed", () => {
  const empty = { email: "", birthdate: "", national_id: "", monthly_income: "" };

  it("only checks the fields the provider asks for", () => {
    expect(validate(["email"], { ...empty, email: "anna@example.com" })).toEqual({});
    expect(validate(["email"], { ...empty, email: "anna" })).toEqual({ email: "Enter a valid email" });
    expect(validate(["email", "birthdate"], { ...empty, email: "a@b.co" })).toEqual({ birthdate: "Enter your date of birth" });
  });

  it("refuses a birthday in the future, a short ID and a missing income", () => {
    const today = new Date("2026-10-19");
    expect(validate(["birthdate"], { ...empty, birthdate: "2030-01-01" }, today)).toEqual({ birthdate: "Enter your date of birth" });
    expect(validate(["birthdate"], { ...empty, birthdate: "1990-01-15" }, today)).toEqual({});
    expect(validate(["national_id"], { ...empty, national_id: "AB1" }).national_id).toBeDefined();
    expect(validate(["national_id"], { ...empty, national_id: "AB123456" })).toEqual({});
    expect(validate(["monthly_income"], empty).monthly_income).toBeDefined();
    expect(validate(["monthly_income"], { ...empty, monthly_income: "-5" }).monthly_income).toBeDefined();
    expect(validate(["monthly_income"], { ...empty, monthly_income: "3000" })).toEqual({});
  });
});

describe("pay in instalments at the checkout", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.getSession).mockResolvedValue(session);
    vi.mocked(bnpl.getBnplOffers).mockResolvedValue(offers);
  });

  it("offers the tab only when a provider has something to offer", async () => {
    renderCheckout();
    expect(await screen.findByRole("tab", { name: "Pay in instalments" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Card" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByLabelText("Card number")).toBeVisible();
  });

  it("looks exactly as before when there are no offers or they cannot be loaded", async () => {
    vi.mocked(bnpl.getBnplOffers).mockResolvedValue({ ...offers, offers: [] });
    const { unmount } = renderCheckout();
    expect(await screen.findByLabelText("Card number")).toBeInTheDocument();
    await waitFor(() => expect(bnpl.getBnplOffers).toHaveBeenCalled());
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    unmount();

    vi.mocked(bnpl.getBnplOffers).mockRejectedValue(new CheckoutApiError(409, "bnpl_not_available", "Not available"));
    renderCheckout();
    expect(await screen.findByLabelText("Card number")).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });

  it("shows each plan with what is paid, when, and what it costs", async () => {
    renderCheckout();
    await openInstalments();

    const plans = screen.getByRole("group", { name: "Choose a plan" });
    expect(within(plans).getByText("SplitNow: 3 payments of €100.00 every 2 weeks")).toBeInTheDocument();
    expect(within(plans).getByText("Lendo: 12 payments of €26.38 every month")).toBeInTheDocument();
    expect(within(plans).getByText("€16.56 interest (9.9% APR) · total €316.56")).toBeInTheDocument();
    expect(within(plans).getByText("PayLaterEU: €300.00 in 30 days")).toBeInTheDocument();
    expect(within(plans).queryByText("Best option")).not.toBeInTheDocument(); // the customer chooses in this mode
    expect(screen.getByRole("radio", { name: /SplitNow/ })).toBeChecked();
  });

  it("asks only for what the chosen provider needs", async () => {
    renderCheckout();
    await openInstalments();
    const panel = screen.getByRole("form", { name: "Pay in instalments" });
    expect(within(panel).getByLabelText("Email")).toBeInTheDocument();
    expect(within(panel).queryByLabelText("Date of birth")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: /Lendo/ }));
    expect(within(panel).getByLabelText("Date of birth")).toBeInTheDocument();
    expect(within(panel).queryByLabelText("National ID number")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: /KreditKo/ }));
    expect(within(panel).getByLabelText("National ID number")).toBeInTheDocument();
    expect(within(panel).getByLabelText("Monthly income (after tax)")).toBeInTheDocument();
    expect(within(panel).queryByLabelText("Date of birth")).not.toBeInTheDocument();
  });

  it("checks the details before sending anything", async () => {
    renderCheckout();
    await openInstalments();

    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(await screen.findByText("Enter a valid email")).toBeInTheDocument();
    expect(bnpl.applyBnpl).not.toHaveBeenCalled();
  });

  it("applies with the chosen plan and, once approved, hands over to the payment outcome", async () => {
    vi.mocked(bnpl.applyBnpl).mockResolvedValue(application({}));
    renderCheckout();
    await openInstalments();

    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: "Apply for €300.00 in instalments" }));

    expect(bnpl.applyBnpl).toHaveBeenCalledWith("pay_1_secret_x", "splitnow", "splitnow_3", { email: "anna@example.com" });
    expect(await screen.findByText("Approved by SplitNow")).toBeInTheDocument();

    // The platform completes the payment a moment later; the page looks at the session until it has.
    vi.mocked(api.getSession).mockResolvedValue({ ...session, status: "succeeded" });
    expect(await screen.findByText("Payment successful", {}, { timeout: 5000 })).toBeInTheDocument();
  }, 10000);

  it("waits for a provider that decides later and shows its answer", async () => {
    vi.mocked(bnpl.applyBnpl).mockResolvedValue(
      application({ id: "bnpl_2", provider: "lendo", provider_name: "Lendo", plan: "lendo_12", status: "pending_review", awaiting_decision: true,
        message: "The provider is looking at your application. This can take a moment." }),
    );
    vi.mocked(bnpl.getBnplApplication).mockResolvedValue(
      application({ id: "bnpl_2", provider: "lendo", provider_name: "Lendo", plan: "lendo_12", status: "pending_review", awaiting_decision: true }),
    );
    renderCheckout();
    await openInstalments();
    await userEvent.click(screen.getByRole("radio", { name: /Lendo/ }));
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.type(instalments().getByLabelText("Date of birth"), "1990-01-15");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(bnpl.applyBnpl).toHaveBeenCalledWith("pay_1_secret_x", "lendo", "lendo_12", { email: "anna@example.com", birthdate: "1990-01-15" });
    expect(await screen.findByText("Waiting for Lendo")).toBeInTheDocument();

    vi.mocked(bnpl.getBnplApplication).mockResolvedValue(
      application({ id: "bnpl_2", provider: "lendo", provider_name: "Lendo", plan: "lendo_12", status: "approved" }),
    );
    expect(await screen.findByText("Approved by Lendo", {}, { timeout: 5000 })).toBeInTheDocument();
  }, 10000);

  it("sends the income in cents and the ID as typed", async () => {
    vi.mocked(bnpl.applyBnpl).mockResolvedValue(application({ provider: "kreditko", provider_name: "KreditKo", plan: "kreditko_12", status: "declined" }));
    renderCheckout();
    await openInstalments();
    await userEvent.click(screen.getByRole("radio", { name: /KreditKo/ }));
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.type(instalments().getByLabelText("National ID number"), "AB123456");
    await userEvent.type(instalments().getByLabelText("Monthly income (after tax)"), "3000.50");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(bnpl.applyBnpl).toHaveBeenCalledWith("pay_1_secret_x", "kreditko", "kreditko_12", {
      email: "anna@example.com",
      national_id: "AB123456",
      monthly_income: 300050,
    });
  });

  it("explains a decline and lets the customer choose again", async () => {
    vi.mocked(bnpl.applyBnpl).mockResolvedValue(
      application({ status: "declined", message: "The provider could not approve this application. You can choose another option." }),
    );
    renderCheckout();
    await openInstalments();
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(await screen.findByText("That did not work out")).toBeInTheDocument();
    expect(screen.getByText(/could not approve this application/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Choose another option" }));
    expect(screen.getByRole("form", { name: "Pay in instalments" })).toBeInTheDocument();
  });

  it("shows the provider's reason when it could not take the application", async () => {
    vi.mocked(bnpl.applyBnpl).mockRejectedValue(
      new CheckoutApiError(502, "provider_unavailable", "SplitNow could not take the application. You can try another option."),
    );
    renderCheckout();
    await openInstalments();
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(await screen.findByText("We could not send your application. Please try again.")).toBeInTheDocument();
  });

  it("lets the customer give up while waiting", async () => {
    const waiting = application({ id: "bnpl_3", status: "pending_review", awaiting_decision: true, provider: "lendo", provider_name: "Lendo" });
    vi.mocked(bnpl.applyBnpl).mockResolvedValue(waiting);
    vi.mocked(bnpl.getBnplApplication).mockResolvedValue(waiting);
    vi.mocked(bnpl.cancelBnplApplication).mockResolvedValue(application({ id: "bnpl_3", status: "canceled", message: "The application was canceled." }));
    renderCheckout();
    await openInstalments();
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));
    await userEvent.click(await screen.findByRole("button", { name: "Cancel the application" }));

    expect(bnpl.cancelBnplApplication).toHaveBeenCalledWith("pay_1_secret_x", "bnpl_3");
    expect(await screen.findByText("The application was canceled.")).toBeInTheDocument();
  });

  it("with a routing mode that picks, offers the best option and the consent to move on", async () => {
    vi.mocked(bnpl.getBnplOffers).mockResolvedValue({ ...offers, routing_mode: "cheapest", recommended: { provider: "paylatereu", plan: "paylatereu_30" } });
    vi.mocked(bnpl.applyBnplBest).mockResolvedValue({
      object: "bnpl_best",
      application: application({ provider: "lendo", provider_name: "Lendo", status: "declined" }),
      attempts: [
        { id: "bnpl_a", provider: "paylatereu", provider_name: "PayLaterEU", status: "declined" },
        { id: "bnpl_b", provider: "lendo", provider_name: "Lendo", status: "declined" },
      ],
    });
    renderCheckout();
    await openInstalments();

    expect(screen.getByRole("radio", { name: /Best option/ })).toBeChecked();
    const consent = screen.getByRole("checkbox", { name: /try the next best ones/ });
    expect(consent).toBeChecked();
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(bnpl.applyBnplBest).toHaveBeenCalledWith("pay_1_secret_x", { email: "anna@example.com" }, true);
    expect(await screen.findByTestId("tried")).toHaveTextContent("We asked PayLaterEU (declined), then Lendo (declined).");
  });

  it("does not ask for the next best provider when the customer did not agree", async () => {
    vi.mocked(bnpl.getBnplOffers).mockResolvedValue({ ...offers, routing_mode: "cheapest" });
    vi.mocked(bnpl.applyBnplBest).mockResolvedValue({
      object: "bnpl_best",
      application: application({ status: "declined" }),
      attempts: [{ id: "bnpl_a", provider: "splitnow", provider_name: "SplitNow", status: "declined" }],
    });
    renderCheckout();
    await openInstalments();
    await userEvent.click(screen.getByRole("checkbox", { name: /try the next best ones/ }));
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));

    expect(bnpl.applyBnplBest).toHaveBeenCalledWith("pay_1_secret_x", { email: "anna@example.com" }, false);
  });

  it("keeps the application when the customer looks at the card form meanwhile", async () => {
    const waiting = application({ id: "bnpl_4", status: "pending_review", awaiting_decision: true, provider: "lendo", provider_name: "Lendo" });
    vi.mocked(bnpl.applyBnpl).mockResolvedValue(waiting);
    vi.mocked(bnpl.getBnplApplication).mockResolvedValue(waiting);
    renderCheckout();
    await openInstalments();
    await userEvent.type(instalments().getByLabelText("Email"), "anna@example.com");
    await userEvent.click(screen.getByRole("button", { name: /Apply for/ }));
    expect(await screen.findByText("Waiting for Lendo")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "Card" }));
    expect(screen.getByLabelText("Card number")).toBeVisible();
    await userEvent.click(screen.getByRole("tab", { name: "Pay in instalments" }));

    expect(screen.getByText("Waiting for Lendo")).toBeVisible();
  });
});
