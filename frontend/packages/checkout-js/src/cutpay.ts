import { Elements, type ElementsOptions } from "./elements";
import type { ElementError } from "./protocol";

export const DEFAULT_CHECKOUT_URL = "http://localhost:5174";

export interface CutpayOptions {
  /** Where the checkout app (and so the card iframe) lives. Defaults to the origin this script was loaded from. */
  checkoutUrl?: string;
  /** Replaces window.location.assign; used by tests. */
  navigate?: (url: string) => void;
}

export interface PaymentIntentResult {
  id: string;
  status: string;
  amount: number;
  currency: string;
}

export interface ConfirmPaymentOptions {
  elements: Elements;
  /** Where to send the customer afterwards, when {@code redirect} is "always". */
  returnUrl?: string;
  /** "if_required" (default) only redirects for 3-D Secure; "always" also redirects after a successful payment. */
  redirect?: "if_required" | "always";
  /** The customer's email, for receipts. */
  receiptEmail?: string;
}

export interface ConfirmPaymentResult {
  paymentIntent?: PaymentIntentResult;
  error?: ElementError;
}

export interface Cutpay {
  readonly publishableKey: string;
  elements(options: ElementsOptions): Elements;
  confirmPayment(options: ConfirmPaymentOptions): Promise<ConfirmPaymentResult>;
  redirectToCheckout(options: { clientSecret?: string; url?: string }): void;
}

/** The origin this script came from, when loaded with a plain script tag. */
function scriptOrigin(): string | undefined {
  try {
    const script = document.currentScript;
    return script instanceof HTMLScriptElement && script.src ? new URL(script.src).origin : undefined;
  } catch {
    return undefined;
  }
}
const LOADED_FROM = typeof document === "undefined" ? undefined : scriptOrigin();

const PUBLISHABLE_KEY = /^pk_(test|live)_[0-9A-Za-z]{32}$/;

export function createCutpay(publishableKey: string, options: CutpayOptions = {}): Cutpay {
  if (typeof publishableKey !== "string" || !PUBLISHABLE_KEY.test(publishableKey)) {
    // A secret key here would end up in page source, so say so loudly.
    const looksSecret = typeof publishableKey === "string" && /^[sr]k_/.test(publishableKey);
    throw new Error(
      looksSecret
        ? "Cutpay: never use a secret key in the browser. Use your publishable key (pk_test_… or pk_live_…)."
        : "Cutpay: expected a publishable key (pk_test_… or pk_live_…).",
    );
  }
  const checkoutUrl = (options.checkoutUrl ?? LOADED_FROM ?? DEFAULT_CHECKOUT_URL).replace(/\/+$/, "");
  const navigate = options.navigate ?? ((url: string) => window.location.assign(url));

  return {
    publishableKey,

    elements({ clientSecret }) {
      if (!clientSecret || !/^pay_[0-9A-Za-z]+_secret_/.test(clientSecret)) {
        throw new Error("Cutpay: elements() needs the client_secret of a payment (pay_…_secret_…).");
      }
      return new Elements(clientSecret, publishableKey, checkoutUrl);
    },

    async confirmPayment({ elements, returnUrl, redirect = "if_required", receiptEmail }) {
      const card = elements.getElement("card");
      if (!card || !card.isMounted) {
        return {
          error: { type: "validation_error", code: "element_not_mounted", message: "Mount the card element first." },
        };
      }
      const result = await card.confirm(elements.clientSecret, receiptEmail);
      if (!result.ok) return { error: result.error };

      const { session } = result;
      const paymentIntent: PaymentIntentResult = {
        id: session.id,
        status: session.status,
        amount: session.amount,
        currency: session.currency,
      };
      if (session.status === "failed") {
        return {
          paymentIntent,
          error: {
            type: "card_error",
            code: session.failure_code ?? "card_declined",
            message: session.failure_message ?? "Your card was declined.",
          },
        };
      }
      if (session.status === "requires_action") {
        // The bank's challenge page runs on the hosted checkout, so the customer continues there and is
        // sent to the payment's return_url afterwards.
        navigate(`${checkoutUrl}/c/${encodeURIComponent(elements.clientSecret)}`);
        return new Promise<ConfirmPaymentResult>(() => {});
      }
      if (redirect === "always" && returnUrl) {
        navigate(returnUrl);
        return new Promise<ConfirmPaymentResult>(() => {});
      }
      return { paymentIntent };
    },

    redirectToCheckout({ clientSecret, url }) {
      if (url) {
        // Only ever our own checkout: otherwise a bug upstream could turn this into an open redirect.
        if (new URL(url).origin !== new URL(checkoutUrl).origin) {
          throw new Error("Cutpay: redirectToCheckout() only accepts checkout URLs from " + checkoutUrl);
        }
        navigate(url);
        return;
      }
      if (!clientSecret) throw new Error("Cutpay: redirectToCheckout() needs a clientSecret or a url.");
      navigate(`${checkoutUrl}/c/${encodeURIComponent(clientSecret)}`);
    },
  };
}
