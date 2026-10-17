import {
  parseParentMessage,
  wrap,
  type ChangeState,
  type ConfirmResult,
  type FrameMessage,
  type SessionSummary,
} from "@cutpay/checkout-js/protocol";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CheckoutApiError, confirm, tokenize, type Session } from "./api";
import { detectBrand, digitsOnly, formatCardNumber, formatExpiry, isExpired, luhnValid, parseExpiry } from "./card";

/** The origin of the page that embedded us, from the URL the SDK built. Null when missing or not a real origin. */
export function parentOriginFrom(search: string): string | null {
  const raw = new URLSearchParams(search).get("origin");
  if (!raw) return null;
  try {
    const url = new URL(raw);
    return (url.protocol === "https:" || url.protocol === "http:") && url.origin === raw ? raw : null;
  } catch {
    return null;
  }
}

const cvcLength = (number: string) => (detectBrand(number) === "amex" ? 4 : 3);

function validate(number: string, expiry: string, cvc: string): string | null {
  if (!luhnValid(number)) return "Your card number is invalid.";
  const parsed = parseExpiry(expiry);
  if (!parsed || isExpired(parsed.month, parsed.year)) return "Your card's expiry date is invalid.";
  if (digitsOnly(cvc).length !== cvcLength(number)) return "Your card's security code is incomplete.";
  return null;
}

function summarize(session: Session): SessionSummary {
  return {
    id: session.id,
    status: session.status,
    amount: session.amount,
    currency: session.currency,
    failure_code: session.failure_code,
    failure_message: session.failure_message,
    next_action: session.next_action,
    return_url: session.return_url,
  };
}

const field =
  "w-full bg-transparent px-3 py-2.5 text-[15px] text-fg placeholder:text-muted/70 outline-none " +
  "focus:bg-brand-50/40";

/**
 * The card input that merchants embed with the SDK. It lives on our origin, so the card number is typed into
 * our page, sent to the card vault, and only a payment result is handed back to the merchant's page.
 */
export function CardElementFrame() {
  const parentOrigin = useMemo(() => parentOriginFrom(window.location.search), []);
  const publishableKey = useMemo(() => new URLSearchParams(window.location.search).get("key") ?? undefined, []);
  const framed = window.parent !== window;

  const [number, setNumber] = useState("");
  const [expiry, setExpiry] = useState("");
  const [cvc, setCvc] = useState("");
  const [touched, setTouched] = useState(false);
  const numberRef = useRef<HTMLInputElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  const problem = validate(number, expiry, cvc);
  const empty = !number && !expiry && !cvc;
  const state: ChangeState = {
    complete: problem === null,
    empty,
    brand: detectBrand(number),
    error: touched && !empty ? problem : null,
  };

  const send = useCallback(
    (message: FrameMessage) => {
      if (parentOrigin && framed) window.parent.postMessage(wrap(message), parentOrigin);
    },
    [parentOrigin, framed],
  );

  // Transparent page, so the merchant's background shows through.
  useEffect(() => {
    document.documentElement.style.background = "transparent";
    document.body.style.background = "transparent";
  }, []);

  useEffect(() => {
    send({ type: "ready" });
  }, [send]);

  const stateKey = JSON.stringify(state);
  useEffect(() => {
    send({ type: "change", state: JSON.parse(stateKey) as ChangeState });
  }, [send, stateKey]);

  useEffect(() => {
    const root = rootRef.current;
    if (!root || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => send({ type: "height", height: Math.ceil(root.getBoundingClientRect().height) }));
    observer.observe(root);
    return () => observer.disconnect();
  }, [send]);

  useEffect(() => {
    if (!parentOrigin) return;
    const onMessage = async (event: MessageEvent) => {
      // Only our embedder, and only the window that embedded us.
      if (event.origin !== parentOrigin || event.source !== window.parent) return;
      const message = parseParentMessage(event.data);
      if (!message) return;
      if (message.type === "focus") {
        numberRef.current?.focus();
      } else if (message.type === "clear") {
        setNumber("");
        setExpiry("");
        setCvc("");
        setTouched(false);
      } else {
        const result = await pay(message.clientSecret, message.email);
        send({ type: "confirm_result", requestId: message.requestId, result });
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
    // `pay` reads the latest field values through the ref below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [parentOrigin, send]);

  const latest = useRef({ number, expiry, cvc });
  latest.current = { number, expiry, cvc };

  async function pay(clientSecret: string, email: string | undefined): Promise<ConfirmResult> {
    const { number, expiry, cvc } = latest.current;
    const invalid = validate(number, expiry, cvc);
    if (invalid) {
      setTouched(true);
      return { ok: false, error: { type: "validation_error", code: "incomplete_card", message: invalid } };
    }
    const parsed = parseExpiry(expiry)!;
    try {
      // The card goes to the vault only; payments sees the token.
      const token = await tokenize({ number, exp_month: parsed.month, exp_year: parsed.year, cvc }, publishableKey);
      const session = await confirm(clientSecret, token, email ?? "", publishableKey);
      return { ok: true, session: summarize(session) };
    } catch (error) {
      if (error instanceof CheckoutApiError && error.status < 500) {
        return {
          ok: false,
          error: { type: error.status === 422 || error.code.includes("card") ? "card_error" : "api_error", code: error.code, message: error.message },
        };
      }
      return { ok: false, error: { type: "api_error", code: "network_error", message: "We could not process your payment. Please try again." } };
    }
  }

  if (!parentOrigin || !framed) {
    return <p className="p-2 text-xs text-muted">This input is meant to be embedded with the Cutpay SDK.</p>;
  }

  return (
    <div ref={rootRef}>
      <div
        className="flex overflow-hidden rounded-lg border border-border bg-surface"
        onFocus={() => send({ type: "focus" })}
        onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget)) {
            setTouched(true);
            send({ type: "blur" });
          }
        }}
      >
        <input
          ref={numberRef}
          aria-label="Card number"
          inputMode="numeric"
          autoComplete="cc-number"
          placeholder="Card number"
          value={number}
          onChange={(event) => setNumber(formatCardNumber(event.target.value))}
          className={`${field} min-w-0 flex-[2.2]`}
        />
        <input
          aria-label="Expiry date"
          inputMode="numeric"
          autoComplete="cc-exp"
          placeholder="MM / YY"
          value={expiry}
          onChange={(event) => setExpiry(formatExpiry(event.target.value))}
          className={`${field} min-w-0 flex-1 border-l border-border`}
        />
        <input
          aria-label="Security code"
          inputMode="numeric"
          autoComplete="cc-csc"
          placeholder="CVC"
          maxLength={4}
          value={cvc}
          onChange={(event) => setCvc(digitsOnly(event.target.value))}
          className={`${field} min-w-0 flex-[0.8] border-l border-border`}
        />
      </div>
      {state.error && (
        <p role="alert" className="mt-1 text-xs text-danger">
          {state.error}
        </p>
      )}
    </div>
  );
}
