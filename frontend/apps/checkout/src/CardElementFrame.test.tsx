import { wrap } from "@cutpay/checkout-js/protocol";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CheckoutApiError, type Session } from "./api";
import { CardElementFrame, parentOriginFrom } from "./CardElementFrame";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  tokenize: vi.fn(),
  confirm: vi.fn(),
}));

const api = await import("./api");

const PARENT_ORIGIN = "https://shop.test";
const KEY = "pk_test_" + "a".repeat(32);
const SECRET = "pay_1_secret_x";

const session: Session = {
  id: "pay_1",
  status: "succeeded",
  amount: 2500,
  currency: "EUR",
  description: null,
  merchant_name: "Rose Cafe",
  return_url: null,
  failure_code: null,
  failure_message: null,
  next_action: null,
};

let parent: { postMessage: ReturnType<typeof vi.fn> };
const originalParent = Object.getOwnPropertyDescriptor(window, "parent")!;

function embedAs(origin: string | null) {
  const query = new URLSearchParams({ key: KEY });
  if (origin) query.set("origin", origin);
  window.history.replaceState({}, "", `/elements/card?${query.toString()}`);
}

function fromParent(data: unknown, origin = PARENT_ORIGIN, source: unknown = parent) {
  act(() => {
    window.dispatchEvent(new MessageEvent("message", { data, origin, source: source as MessageEventSource }));
  });
}

const sentMessages = () => parent.postMessage.mock.calls.map(([message]) => message as { type: string });

async function typeValidCard() {
  await userEvent.type(screen.getByLabelText("Card number"), "4242424242424242");
  await userEvent.type(screen.getByLabelText("Expiry date"), "1299");
  await userEvent.type(screen.getByLabelText("Security code"), "123");
}

beforeEach(() => {
  parent = { postMessage: vi.fn() };
  Object.defineProperty(window, "parent", { value: parent, configurable: true });
  embedAs(PARENT_ORIGIN);
  vi.mocked(api.tokenize).mockReset();
  vi.mocked(api.confirm).mockReset();
});

afterEach(() => {
  Object.defineProperty(window, "parent", originalParent);
});

describe("parentOriginFrom", () => {
  it("accepts a plain origin and nothing else", () => {
    expect(parentOriginFrom("?origin=https%3A%2F%2Fshop.test")).toBe("https://shop.test");
    expect(parentOriginFrom("?origin=http%3A%2F%2Flocalhost%3A4000")).toBe("http://localhost:4000");
    expect(parentOriginFrom("")).toBeNull();
    expect(parentOriginFrom("?origin=*")).toBeNull();
    expect(parentOriginFrom("?origin=https%3A%2F%2Fshop.test%2Fpath")).toBeNull();
    expect(parentOriginFrom("?origin=javascript%3Aalert(1)")).toBeNull();
  });
});

describe("the embedded card input", () => {
  it("refuses to run without a valid embedder origin", () => {
    embedAs(null);
    render(<CardElementFrame />);
    expect(screen.getByText(/meant to be embedded/)).toBeInTheDocument();
    expect(parent.postMessage).not.toHaveBeenCalled();
  });

  it("announces itself and reports changes, always naming the embedder's origin", async () => {
    render(<CardElementFrame />);
    expect(sentMessages().map((m) => m.type)).toContain("ready");
    await typeValidCard();
    await waitFor(() => {
      const changes = sentMessages().filter((m) => m.type === "change") as unknown as { state: { complete: boolean; brand: string } }[];
      expect(changes.at(-1)!.state).toMatchObject({ complete: true, brand: "visa" });
    });
    for (const [, targetOrigin] of parent.postMessage.mock.calls) expect(targetOrigin).toBe(PARENT_ORIGIN);
  });

  it("never puts card data in a message", async () => {
    render(<CardElementFrame />);
    await typeValidCard();
    const everything = JSON.stringify(parent.postMessage.mock.calls);
    expect(everything).not.toContain("4242");
    expect(everything).not.toContain("1299");
  });

  it("pays when the embedder asks: card to the vault, token to payments, only the result back", async () => {
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirm).mockResolvedValue(session);
    render(<CardElementFrame />);
    await typeValidCard();

    fromParent(wrap({ type: "confirm", requestId: "r1", clientSecret: SECRET, email: "anna@example.com" }));

    await waitFor(() => expect(sentMessages().some((m) => m.type === "confirm_result")).toBe(true));
    expect(api.tokenize).toHaveBeenCalledWith({ number: "4242 4242 4242 4242", exp_month: 12, exp_year: 2099, cvc: "123" }, KEY);
    expect(api.confirm).toHaveBeenCalledWith(SECRET, "tok_1", "anna@example.com", KEY);
    const result = sentMessages().find((m) => m.type === "confirm_result") as unknown as { requestId: string; result: { ok: boolean; session: { status: string } } };
    expect(result.requestId).toBe("r1");
    expect(result.result.ok).toBe(true);
    expect(result.result.session.status).toBe("succeeded");
    expect(JSON.stringify(result)).not.toContain("tok_1");
  });

  it("rejects an incomplete card without calling the vault", async () => {
    render(<CardElementFrame />);
    await userEvent.type(screen.getByLabelText("Card number"), "4242");

    fromParent(wrap({ type: "confirm", requestId: "r2", clientSecret: SECRET }));

    await waitFor(() => expect(sentMessages().some((m) => m.type === "confirm_result")).toBe(true));
    expect(api.tokenize).not.toHaveBeenCalled();
    const result = sentMessages().find((m) => m.type === "confirm_result") as unknown as { result: { ok: boolean; error: { code: string } } };
    expect(result.result).toMatchObject({ ok: false, error: { code: "incomplete_card" } });
    expect(await screen.findByRole("alert")).toHaveTextContent(/card number is invalid/i);
  });

  it("reports a rejected card as a card error", async () => {
    vi.mocked(api.tokenize).mockRejectedValue(new CheckoutApiError(422, "invalid_card", "That card was not accepted."));
    render(<CardElementFrame />);
    await typeValidCard();
    fromParent(wrap({ type: "confirm", requestId: "r3", clientSecret: SECRET }));
    await waitFor(() => expect(sentMessages().some((m) => m.type === "confirm_result")).toBe(true));
    const result = sentMessages().find((m) => m.type === "confirm_result") as unknown as { result: { error: { type: string; message: string } } };
    expect(result.result.error).toMatchObject({ type: "card_error", message: "That card was not accepted." });
  });

  it("hides server failures behind a generic message", async () => {
    vi.mocked(api.tokenize).mockRejectedValue(new Error("connect ECONNREFUSED 10.0.0.5:8083"));
    render(<CardElementFrame />);
    await typeValidCard();
    fromParent(wrap({ type: "confirm", requestId: "r4", clientSecret: SECRET }));
    await waitFor(() => expect(sentMessages().some((m) => m.type === "confirm_result")).toBe(true));
    const result = sentMessages().find((m) => m.type === "confirm_result") as unknown as { result: { error: { code: string; message: string } } };
    expect(result.result.error.code).toBe("network_error");
    expect(result.result.error.message).not.toContain("10.0.0.5");
  });

  it("ignores requests from any other origin or window", async () => {
    vi.mocked(api.tokenize).mockResolvedValue("tok_1");
    vi.mocked(api.confirm).mockResolvedValue(session);
    render(<CardElementFrame />);
    await typeValidCard();
    const request = wrap({ type: "confirm", requestId: "evil", clientSecret: SECRET });

    fromParent(request, "https://evil.test");
    fromParent(request, PARENT_ORIGIN, window);
    fromParent({ type: "confirm", requestId: "evil", clientSecret: SECRET });
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(api.tokenize).not.toHaveBeenCalled();
    expect(sentMessages().some((m) => m.type === "confirm_result")).toBe(false);
  });

  it("clears the fields when asked", async () => {
    render(<CardElementFrame />);
    await typeValidCard();
    fromParent(wrap({ type: "clear" }));
    await waitFor(() => expect(screen.getByLabelText("Card number")).toHaveValue(""));
  });
});
