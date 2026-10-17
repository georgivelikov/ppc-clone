import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createCutpay } from "./cutpay";
import { parseFrameMessage, parseParentMessage, wrap, type ConfirmResult, type SessionSummary } from "./protocol";

const KEY = "pk_test_" + "a".repeat(32);
const CHECKOUT = "http://checkout.test:5174";
const SECRET = "pay_01ABC_secret_xyz";

const session = (over: Partial<SessionSummary> = {}): SessionSummary => ({
  id: "pay_01ABC",
  status: "succeeded",
  amount: 2500,
  currency: "EUR",
  failure_code: null,
  failure_message: null,
  next_action: null,
  return_url: null,
  ...over,
});

/** Delivers a message to the page the way a browser would: with the sender's origin and window. */
function fromFrame(iframe: HTMLIFrameElement, data: unknown, origin = CHECKOUT, source: Window | null = iframe.contentWindow) {
  window.dispatchEvent(new MessageEvent("message", { data, origin, source }));
}

let host: HTMLDivElement;

beforeEach(() => {
  host = document.createElement("div");
  host.id = "card";
  document.body.appendChild(host);
});
afterEach(() => {
  host.remove();
  vi.restoreAllMocks();
});

function setup(navigate = vi.fn()) {
  const cutpay = createCutpay(KEY, { checkoutUrl: CHECKOUT, navigate });
  const elements = cutpay.elements({ clientSecret: SECRET });
  const card = elements.create("card");
  card.mount("#card");
  const iframe = host.querySelector("iframe")!;
  const post = vi.spyOn(iframe.contentWindow!, "postMessage");
  return { cutpay, elements, card, iframe, post, navigate };
}

describe("setup", () => {
  it("refuses secret keys in the browser", () => {
    expect(() => createCutpay("sk_test_" + "a".repeat(32))).toThrow(/never use a secret key/i);
    expect(() => createCutpay("rk_live_" + "a".repeat(32))).toThrow(/never use a secret key/i);
    expect(() => createCutpay("nonsense")).toThrow(/publishable key/);
  });

  it("needs a payment client secret", () => {
    const cutpay = createCutpay(KEY, { checkoutUrl: CHECKOUT });
    expect(() => cutpay.elements({ clientSecret: "" })).toThrow();
    expect(() => cutpay.elements({ clientSecret: "whsec_nope" })).toThrow();
  });
});

describe("mounting", () => {
  it("puts an iframe from the checkout origin on the page, without the client secret in its URL", () => {
    const { iframe } = setup();
    const url = new URL(iframe.src);
    expect(url.origin).toBe(CHECKOUT);
    expect(url.pathname).toBe("/elements/card");
    expect(url.searchParams.get("key")).toBe(KEY);
    expect(url.searchParams.get("origin")).toBe(window.location.origin);
    expect(iframe.src).not.toContain("secret");
    expect(iframe.getAttribute("sandbox")).toBe("allow-scripts allow-same-origin");
  });

  it("cannot be mounted twice, and unmounting removes the frame", () => {
    const { card } = setup();
    expect(() => card.mount("#card")).toThrow(/already mounted/);
    card.unmount();
    expect(host.querySelector("iframe")).toBeNull();
    expect(card.isMounted).toBe(false);
  });

  it("fails clearly when the target does not exist", () => {
    const card = createCutpay(KEY, { checkoutUrl: CHECKOUT }).elements({ clientSecret: SECRET }).create("card");
    expect(() => card.mount("#missing")).toThrow(/no element matches/);
  });
});

describe("events from the frame", () => {
  it("reports ready and change events, and resizes the frame", () => {
    const { card, iframe } = setup();
    const ready = vi.fn();
    const change = vi.fn();
    card.on("ready", ready).on("change", change);

    fromFrame(iframe, wrap({ type: "ready" }));
    fromFrame(iframe, wrap({ type: "change", state: { complete: true, empty: false, brand: "visa", error: null } }));
    fromFrame(iframe, wrap({ type: "height", height: 80 }));

    expect(ready).toHaveBeenCalledOnce();
    expect(change).toHaveBeenCalledWith({ complete: true, empty: false, brand: "visa", error: null });
    expect(card.currentState.complete).toBe(true);
    expect(iframe.style.height).toBe("80px");
  });

  it("ignores messages from another origin, another window, or without the envelope", () => {
    const { card, iframe } = setup();
    const change = vi.fn();
    card.on("change", change);
    const state = { complete: true, empty: false, brand: "visa", error: null };

    fromFrame(iframe, wrap({ type: "change", state }), "http://evil.test");
    fromFrame(iframe, wrap({ type: "change", state }), CHECKOUT, window);
    fromFrame(iframe, { type: "change", state });
    fromFrame(iframe, "change");
    expect(change).not.toHaveBeenCalled();
  });
});

describe("confirmPayment", () => {
  function answer(iframe: HTMLIFrameElement, post: ReturnType<typeof vi.spyOn>, result: ConfirmResult) {
    const message = post.mock.calls.at(-1)![0] as { requestId: string };
    fromFrame(iframe, wrap({ type: "confirm_result", requestId: message.requestId, result }));
  }

  it("asks the frame to pay, naming the checkout origin, and returns the payment intent", async () => {
    const { cutpay, elements, iframe, post } = setup();
    const promise = cutpay.confirmPayment({ elements, receiptEmail: "ivan@example.com" });

    const [message, target] = post.mock.calls.at(-1)!;
    expect(target).toBe(CHECKOUT);
    expect(parseParentMessage(message)).toMatchObject({ type: "confirm", clientSecret: SECRET, email: "ivan@example.com" });

    answer(iframe, post, { ok: true, session: session() });
    expect(await promise).toEqual({ paymentIntent: { id: "pay_01ABC", status: "succeeded", amount: 2500, currency: "EUR" } });
  });

  it("turns a declined card into a card error", async () => {
    const { cutpay, elements, iframe, post } = setup();
    const promise = cutpay.confirmPayment({ elements });
    answer(iframe, post, {
      ok: true,
      session: session({ status: "failed", failure_code: "card_declined", failure_message: "Your card was declined." }),
    });
    const result = await promise;
    expect(result.error).toEqual({ type: "card_error", code: "card_declined", message: "Your card was declined." });
    expect(result.paymentIntent?.status).toBe("failed");
  });

  it("passes validation errors from the frame straight through", async () => {
    const { cutpay, elements, iframe, post } = setup();
    const promise = cutpay.confirmPayment({ elements });
    answer(iframe, post, { ok: false, error: { type: "validation_error", code: "incomplete_card", message: "Enter a card." } });
    expect((await promise).error?.code).toBe("incomplete_card");
  });

  it("sends the customer to the hosted page for 3-D Secure", async () => {
    const { cutpay, elements, iframe, post, navigate } = setup();
    const settled = vi.fn();
    void cutpay.confirmPayment({ elements }).then(settled);
    answer(iframe, post, {
      ok: true,
      session: session({ status: "requires_action", next_action: { type: "three_ds", challenge_id: "ch_1" } }),
    });
    await Promise.resolve();
    expect(navigate).toHaveBeenCalledWith(`${CHECKOUT}/c/${SECRET}`);
    expect(settled).not.toHaveBeenCalled(); // the page is leaving, so the promise never settles
  });

  it("redirects after success only when asked to", async () => {
    const first = setup();
    const done = first.cutpay.confirmPayment({ elements: first.elements, returnUrl: "https://shop.test/thanks" });
    answer(first.iframe, first.post, { ok: true, session: session() });
    await done;
    expect(first.navigate).not.toHaveBeenCalled();

    first.card.unmount();
    const second = setup();
    void second.cutpay.confirmPayment({ elements: second.elements, returnUrl: "https://shop.test/thanks", redirect: "always" });
    answer(second.iframe, second.post, { ok: true, session: session() });
    await Promise.resolve();
    expect(second.navigate).toHaveBeenCalledWith("https://shop.test/thanks");
  });

  it("ignores an answer to a request it did not make", async () => {
    vi.useFakeTimers();
    try {
      const { cutpay, elements, iframe } = setup();
      const settled = vi.fn();
      void cutpay.confirmPayment({ elements }).then(settled);
      fromFrame(iframe, wrap({ type: "confirm_result", requestId: "someone-else", result: { ok: true, session: session() } }));
      await vi.advanceTimersByTimeAsync(1000);
      expect(settled).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("gives up when the frame never answers", async () => {
    vi.useFakeTimers();
    try {
      const { cutpay, elements } = setup();
      const promise = cutpay.confirmPayment({ elements });
      await vi.advanceTimersByTimeAsync(61_000);
      expect((await promise).error?.code).toBe("timeout");
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports an unmounted element instead of throwing", async () => {
    const cutpay = createCutpay(KEY, { checkoutUrl: CHECKOUT });
    const elements = cutpay.elements({ clientSecret: SECRET });
    elements.create("card");
    expect((await cutpay.confirmPayment({ elements })).error?.code).toBe("element_not_mounted");
  });
});

describe("redirectToCheckout", () => {
  it("goes to the hosted page for a client secret or a checkout url, and nowhere else", () => {
    const navigate = vi.fn();
    const cutpay = createCutpay(KEY, { checkoutUrl: CHECKOUT, navigate });
    cutpay.redirectToCheckout({ clientSecret: SECRET });
    expect(navigate).toHaveBeenLastCalledWith(`${CHECKOUT}/c/${SECRET}`);
    cutpay.redirectToCheckout({ url: `${CHECKOUT}/c/${SECRET}` });
    expect(navigate).toHaveBeenLastCalledWith(`${CHECKOUT}/c/${SECRET}`);
    expect(() => cutpay.redirectToCheckout({ url: "https://evil.test/c/x" })).toThrow(/only accepts checkout URLs/);
    expect(() => cutpay.redirectToCheckout({})).toThrow();
  });
});

describe("protocol parsing", () => {
  it("accepts only well-formed envelopes", () => {
    expect(parseFrameMessage(wrap({ type: "ready" }))).not.toBeNull();
    expect(parseFrameMessage({ ...wrap({ type: "ready" }), version: 2 })).toBeNull();
    expect(parseFrameMessage({ ...wrap({ type: "ready" }), source: "other" })).toBeNull();
    expect(parseFrameMessage(wrap({ type: "launch_missiles" }))).toBeNull();
    expect(parseFrameMessage(wrap({ type: "height", height: "tall" }))).toBeNull();
    expect(parseFrameMessage(null)).toBeNull();
    expect(parseParentMessage(wrap({ type: "confirm", requestId: "r" }))).toBeNull(); // no client secret
    expect(parseParentMessage(wrap({ type: "confirm", requestId: "r", clientSecret: "s" }))).not.toBeNull();
    expect(parseParentMessage(wrap({ type: "ready" }))).toBeNull();
  });
});
