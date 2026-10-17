// The checkout talks to two public endpoints: the card vault (tokenisation) and the payment session.
// There is no login here — the unguessable client secret in the URL is the credential.

export interface NextAction {
  type: "three_ds";
  challenge_id: string;
}

export interface Session {
  id: string;
  status:
    | "requires_payment_method"
    | "processing"
    | "requires_action"
    | "succeeded"
    | "failed"
    | "canceled"
    | "partially_refunded"
    | "refunded";
  amount: number;
  currency: string;
  description: string | null;
  merchant_name: string | null;
  return_url: string | null;
  failure_code: string | null;
  failure_message: string | null;
  next_action: NextAction | null;
}

export interface CardInput {
  number: string;
  exp_month: number;
  exp_year: number;
  cvc: string;
}

export class CheckoutApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "CheckoutApiError";
  }
}

async function call<T>(method: string, path: string, body?: unknown, publishableKey?: string): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  // Embedded checkouts identify the merchant integration; the gateway ignores it on these public routes.
  if (publishableKey) headers.Authorization = `Bearer ${publishableKey}`;
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let code = "unknown";
    let message = response.statusText || "Request failed";
    try {
      const payload = (await response.json()) as { error?: { code?: string; message?: string } };
      code = payload.error?.code ?? code;
      message = payload.error?.message ?? message;
    } catch {
      // not JSON: keep the generic message
    }
    throw new CheckoutApiError(response.status, code, message);
  }
  return (await response.json()) as T;
}

export function getSession(secret: string): Promise<Session> {
  return call("GET", `/checkout/sessions/${encodeURIComponent(secret)}`);
}

/** Sends the card number to the vault, which hands back a token. Our payments service never sees the number. */
export async function tokenize(card: CardInput, publishableKey?: string): Promise<string> {
  const result = await call<{ token: string }>("POST", "/vault/tokenize", card, publishableKey);
  return result.token;
}

export function confirm(secret: string, token: string, email: string, publishableKey?: string): Promise<Session> {
  return call("POST", `/checkout/sessions/${encodeURIComponent(secret)}/confirm`, { token, email }, publishableKey);
}

/** The customer's answer on the fake bank page. The bank then notifies payments, so re-read the session afterwards. */
export function completeThreeDs(challengeId: string, code: string): Promise<{ status: string }> {
  return call("POST", `/vault/3ds/challenge/${encodeURIComponent(challengeId)}/complete`, { code });
}
