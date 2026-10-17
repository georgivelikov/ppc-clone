import type {
  AuthResponse,
  Balance,
  BalanceSummary,
  BalanceTransactionList,
  BalanceTransactionListParams,
  BankAccount,
  BankAccountList,
  CheckoutSessionCreated,
  CreatePaymentInput,
  CreateBankAccountInput,
  CreateRefundInput,
  LoginInput,
  MeResponse,
  Merchant,
  Payment,
  PaymentList,
  PaymentListParams,
  PaymentSummary,
  Payout,
  PayoutDetail,
  PayoutList,
  PayoutListParams,
  PayoutPreview,
  PayoutReconciliation,
  PayoutSchedule,
  Refund,
  SignupInput,
  UpdateMerchantInput,
  UpdatePayoutScheduleInput,
} from "./types";
import type {
  CreatePaymentLinkInput,
  PaymentLink,
  PaymentLinkList,
  PaymentLinkListParams,
  PaymentLinkPayments,
  PaymentStatusEvent,
  UpdatePaymentLinkInput,
} from "./links";
import type {
  ApiKey,
  ApiKeyList,
  CreateApiKeyInput,
  CreateWebhookEndpointInput,
  CutpayEvent,
  CutpayEventList,
  CutpayEventListParams,
  RequestLogEntry,
  RequestLogList,
  RequestLogParams,
  UpdateWebhookEndpointInput,
  WebhookDelivery,
  WebhookDeliveryList,
  WebhookDeliveryListParams,
  WebhookEndpoint,
  WebhookEndpointList,
} from "./developers";

/** Builds "?a=1&b=2", leaving out empty values; `livemode` is sent only when it is true (the default is test). */
function toQuery(params: object): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === "" || (key === "livemode" && value === false)) continue;
    query.set(key, String(value));
  }
  return query.size > 0 ? `?${query}` : "";
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly type: string,
    readonly code: string,
    message: string,
    readonly param?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface ApiClientOptions {
  /** Base URL of the gateway. Empty means same origin (the Vite dev server proxies to the gateway). */
  baseUrl?: string;
  fetch?: typeof fetch;
  /** Called when the session can no longer be refreshed, so the app can send the user to the login page. */
  onSessionExpired?: () => void;
}

/**
 * Thin fetch wrapper that keeps the access token in memory (never localStorage, so XSS cannot read it)
 * and transparently renews it using the HttpOnly refresh cookie.
 */
export class ApiClient {
  private accessToken: string | null = null;
  private refreshing: Promise<AuthResponse | null> | null = null;
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly onSessionExpired?: () => void;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? "";
    this.fetchImpl = options.fetch ?? ((...args) => fetch(...args));
    this.onSessionExpired = options.onSessionExpired;
  }

  // --- auth ---------------------------------------------------------------

  async signup(input: SignupInput): Promise<AuthResponse> {
    return this.storeSession(await this.request<AuthResponse>("POST", "/auth/signup", input, { auth: false }));
  }

  async login(input: LoginInput): Promise<AuthResponse> {
    return this.storeSession(await this.request<AuthResponse>("POST", "/auth/login", input, { auth: false }));
  }

  /** Restores a session from the refresh cookie, e.g. after a page reload. Resolves null if there is none. */
  restoreSession(): Promise<AuthResponse | null> {
    // Single-flight: concurrent callers (React StrictMode, parallel 401s) share one request, because
    // refresh tokens are single-use and a second concurrent call would look like token theft.
    this.refreshing ??= this.doRefresh().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  async logout(): Promise<void> {
    try {
      await this.request<void>("POST", "/auth/logout", undefined, { auth: false });
    } finally {
      this.accessToken = null;
    }
  }

  // --- resources ----------------------------------------------------------

  me(): Promise<MeResponse> {
    return this.request("GET", "/me");
  }

  getMerchant(): Promise<Merchant> {
    return this.request("GET", "/v1/merchant");
  }

  updateMerchant(input: UpdateMerchantInput): Promise<Merchant> {
    return this.request("PATCH", "/v1/merchant", input);
  }

  // --- payments -----------------------------------------------------------

  /** Creates a payment and returns the hosted checkout URL to send the customer to. */
  createCheckoutSession(input: CreatePaymentInput, livemode = false): Promise<CheckoutSessionCreated> {
    return this.request("POST", `/v1/checkout_sessions${toQuery({ livemode })}`, input, { idempotent: true });
  }

  listPayments(params: PaymentListParams = {}): Promise<PaymentList> {
    return this.request("GET", `/v1/payment_intents${toQuery(params)}`);
  }

  getPayment(id: string): Promise<Payment> {
    return this.request("GET", `/v1/payment_intents/${encodeURIComponent(id)}`);
  }

  cancelPayment(id: string): Promise<Payment> {
    return this.request("POST", `/v1/payment_intents/${encodeURIComponent(id)}/cancel`, undefined, {
      idempotent: true,
    });
  }

  createRefund(input: CreateRefundInput): Promise<Refund> {
    return this.request("POST", "/v1/refunds", input, { idempotent: true });
  }

  paymentSummary(livemode = false): Promise<PaymentSummary> {
    return this.request("GET", `/v1/payment_stats${toQuery({ livemode })}`);
  }

  // --- links, QR codes and the virtual POS --------------------------------

  createPaymentLink(input: CreatePaymentLinkInput, livemode = false): Promise<PaymentLink> {
    return this.request("POST", `/v1/payment_links${toQuery({ livemode })}`, input, { idempotent: true });
  }

  listPaymentLinks(params: PaymentLinkListParams = {}): Promise<PaymentLinkList> {
    return this.request("GET", `/v1/payment_links${toQuery(params)}`);
  }

  getPaymentLink(id: string): Promise<PaymentLink> {
    return this.request("GET", `/v1/payment_links/${encodeURIComponent(id)}`);
  }

  updatePaymentLink(id: string, input: UpdatePaymentLinkInput): Promise<PaymentLink> {
    return this.request("PATCH", `/v1/payment_links/${encodeURIComponent(id)}`, input);
  }

  paymentLinkPayments(id: string): Promise<PaymentLinkPayments> {
    return this.request("GET", `/v1/payment_links/${encodeURIComponent(id)}/payments`);
  }

  /** The QR code as an image. `fg` and `bg` ("#rrggbb") preview colours that are not saved yet. */
  paymentLinkQr(
    id: string,
    options: { format?: "svg" | "png"; size?: number; fg?: string; bg?: string } = {},
  ): Promise<Blob> {
    return this.fetchBlob(`/v1/payment_links/${encodeURIComponent(id)}/qr${toQuery({ format: "svg", ...options })}`);
  }

  /** The print-ready A6 table card, as a PDF. */
  paymentLinkCard(id: string): Promise<Blob> {
    return this.fetchBlob(`/v1/payment_links/${encodeURIComponent(id)}/card.pdf`);
  }

  /** A QR code for one checkout address (the "Charge with QR" screen). */
  checkoutQr(checkoutUrl: string, format: "svg" | "png" = "svg"): Promise<Blob> {
    return this.fetchBlob(`/v1/qr${toQuery({ data: checkoutUrl, format })}`);
  }

  /**
   * Keys in an order taken by phone or mail. The charge is created here; the card is then typed into the card
   * element and paid with the returned `client_secret`. Card-not-present, so the bank does not ask for 3-D Secure.
   */
  createTerminalCharge(input: CreatePaymentInput, livemode = false): Promise<Payment> {
    return this.request("POST", `/v1/terminal/charges${toQuery({ livemode })}`, input, { idempotent: true });
  }

  /**
   * Follows one payment live (Server-Sent Events): calls `onStatus` with the current status first and then on
   * every change. Resolves when the payment reaches an end state, the server closes the stream or `signal` aborts.
   * Uses fetch rather than EventSource because the latter cannot send the Authorization header.
   */
  async watchPayment(id: string, onStatus: (event: PaymentStatusEvent) => void, signal?: AbortSignal): Promise<void> {
    const response = await this.send("GET", `/v1/payment_intents/${encodeURIComponent(id)}/stream`, undefined, {
      accept: "text/event-stream",
      signal,
    });
    if (!response.body) return;
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) return;
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSse(buffer);
        buffer = parsed.rest;
        for (const event of parsed.events) onStatus(event);
      }
    } catch (error) {
      if (signal?.aborted) return;
      throw error;
    } finally {
      reader.cancel().catch(() => undefined);
    }
  }

  // --- balances (ledger) --------------------------------------------------

  getBalance(livemode = false): Promise<Balance> {
    return this.request("GET", `/v1/balance${toQuery({ livemode })}`);
  }

  listBalanceTransactions(params: BalanceTransactionListParams = {}): Promise<BalanceTransactionList> {
    return this.request("GET", `/v1/balance_transactions${toQuery(params)}`);
  }

  /** Daily card volume and fees per currency; days without payments are included as zeros. */
  balanceSummary(days = 14, livemode = false): Promise<BalanceSummary> {
    return this.request("GET", `/v1/balance_summary${toQuery({ days, livemode })}`);
  }

  // --- payouts ------------------------------------------------------------

  listPayouts(params: PayoutListParams = {}): Promise<PayoutList> {
    return this.request("GET", `/v1/payouts${toQuery(params)}`);
  }

  getPayout(id: string): Promise<PayoutDetail> {
    return this.request("GET", `/v1/payouts/${encodeURIComponent(id)}`);
  }

  payoutReconciliation(id: string): Promise<PayoutReconciliation> {
    return this.request("GET", `/v1/payouts/${encodeURIComponent(id)}/reconciliation`);
  }

  payoutPreview(currency: string, livemode = false): Promise<PayoutPreview> {
    return this.request("GET", `/v1/payouts/preview${toQuery({ currency, livemode })}`);
  }

  /** "Pay out now": pays out everything that is available, for a fee. */
  createPayout(currency: string, livemode = false): Promise<Payout> {
    return this.request("POST", `/v1/payouts${toQuery({ livemode })}`, { currency }, { idempotent: true });
  }

  getPayoutSchedule(): Promise<PayoutSchedule> {
    return this.request("GET", "/v1/payout_schedule");
  }

  updatePayoutSchedule(input: UpdatePayoutScheduleInput): Promise<PayoutSchedule> {
    return this.request("PUT", "/v1/payout_schedule", input);
  }

  listBankAccounts(livemode = false): Promise<BankAccountList> {
    return this.request("GET", `/v1/bank_accounts${toQuery({ livemode })}`);
  }

  createBankAccount(input: CreateBankAccountInput, livemode = false): Promise<BankAccount> {
    return this.request("POST", `/v1/bank_accounts${toQuery({ livemode })}`, input, { idempotent: true });
  }

  deleteBankAccount(id: string): Promise<void> {
    return this.request("DELETE", `/v1/bank_accounts/${encodeURIComponent(id)}`);
  }

  // --- developers ---------------------------------------------------------

  listApiKeys(): Promise<ApiKeyList> {
    return this.request("GET", "/v1/api_keys");
  }

  /** The response carries the secret once; the caller must show it, because it can never be fetched again. */
  createApiKey(input: CreateApiKeyInput): Promise<ApiKey> {
    return this.request("POST", "/v1/api_keys", input, { idempotent: true });
  }

  /** Issues a replacement key; the old one stops working after `graceHours` (0 means immediately). */
  rollApiKey(id: string, graceHours = 0): Promise<ApiKey> {
    return this.request("POST", `/v1/api_keys/${encodeURIComponent(id)}/roll`, { grace_hours: graceHours }, { idempotent: true });
  }

  revokeApiKey(id: string): Promise<void> {
    return this.request("DELETE", `/v1/api_keys/${encodeURIComponent(id)}`);
  }

  listWebhookEndpoints(livemode = false): Promise<WebhookEndpointList> {
    return this.request("GET", `/v1/webhook_endpoints${toQuery({ livemode })}`);
  }

  getWebhookEndpoint(id: string): Promise<WebhookEndpoint> {
    return this.request("GET", `/v1/webhook_endpoints/${encodeURIComponent(id)}`);
  }

  /** The response carries the signing secret once. */
  createWebhookEndpoint(input: CreateWebhookEndpointInput, livemode = false): Promise<WebhookEndpoint> {
    return this.request("POST", `/v1/webhook_endpoints${toQuery({ livemode })}`, input);
  }

  updateWebhookEndpoint(id: string, input: UpdateWebhookEndpointInput): Promise<WebhookEndpoint> {
    return this.request("PATCH", `/v1/webhook_endpoints/${encodeURIComponent(id)}`, input);
  }

  deleteWebhookEndpoint(id: string): Promise<void> {
    return this.request("DELETE", `/v1/webhook_endpoints/${encodeURIComponent(id)}`);
  }

  /** Queues a test event for this endpoint only. */
  testWebhookEndpoint(id: string): Promise<{ delivery_id: string; endpoint: string }> {
    return this.request("POST", `/v1/webhook_endpoints/${encodeURIComponent(id)}/test`);
  }

  listWebhookDeliveries(params: WebhookDeliveryListParams = {}): Promise<WebhookDeliveryList> {
    return this.request("GET", `/v1/webhook_deliveries${toQuery(params)}`);
  }

  getWebhookDelivery(id: string): Promise<WebhookDelivery> {
    return this.request("GET", `/v1/webhook_deliveries/${encodeURIComponent(id)}`);
  }

  /** Sends the delivery again right away. */
  retryWebhookDelivery(id: string): Promise<WebhookDelivery> {
    return this.request("POST", `/v1/webhook_deliveries/${encodeURIComponent(id)}/retry`);
  }

  listEvents(params: CutpayEventListParams = {}): Promise<CutpayEventList> {
    return this.request("GET", `/v1/events${toQuery(params)}`);
  }

  getEvent(id: string): Promise<CutpayEvent> {
    return this.request("GET", `/v1/events/${encodeURIComponent(id)}`);
  }

  listRequestLogs(params: RequestLogParams = {}): Promise<RequestLogList> {
    return this.request("GET", `/v1/logs${toQuery(params)}`);
  }

  getRequestLog(id: string): Promise<RequestLogEntry> {
    return this.request("GET", `/v1/logs/${encodeURIComponent(id)}`);
  }

  // --- internals ----------------------------------------------------------

  private storeSession(session: AuthResponse): AuthResponse {
    this.accessToken = session.access_token;
    return session;
  }

  private async doRefresh(): Promise<AuthResponse | null> {
    try {
      return this.storeSession(await this.request<AuthResponse>("POST", "/auth/refresh", undefined, { auth: false }));
    } catch (error) {
      this.accessToken = null;
      if (error instanceof ApiError && error.status === 401) {
        return null;
      }
      throw error;
    }
  }

  private async request<T>(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    const response = await this.send(method, path, body, options);
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  private async fetchBlob(path: string): Promise<Blob> {
    const response = await this.send("GET", path, undefined, { accept: "*/*" });
    return response.blob();
  }

  /** One authenticated call, renewing the access token once if it has expired. Throws ApiError on any non-2xx. */
  private async send(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<Response> {
    const useAuth = options.auth ?? true;
    const headers: Record<string, string> = { Accept: options.accept ?? "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (useAuth && this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`;
    // One key per user action, reused on the automatic retry after a token refresh so it cannot double-submit.
    const idempotencyKey = options.idempotencyKey ?? (options.idempotent ? crypto.randomUUID() : undefined);
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

    const response = await this.fetchImpl(this.baseUrl + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "include",
      signal: options.signal,
    });

    if (response.status === 401 && useAuth && !options.retried) {
      const session = await this.restoreSession();
      if (session) return this.send(method, path, body, { ...options, retried: true, idempotencyKey });
      this.onSessionExpired?.();
    }

    if (!response.ok) throw await toApiError(response);
    return response;
  }
}

interface RequestOptions {
  auth?: boolean;
  retried?: boolean;
  idempotent?: boolean;
  idempotencyKey?: string;
  accept?: string;
  signal?: AbortSignal;
}

/**
 * Reads the complete events out of a Server-Sent Events buffer and returns what is left over (a partial event
 * waits for the next chunk). Only "status" events with JSON data are understood; comments and others are skipped.
 */
export function parseSse(buffer: string): { events: PaymentStatusEvent[]; rest: string } {
  const events: PaymentStatusEvent[] = [];
  const blocks = buffer.split(/\r?\n\r?\n/);
  const rest = blocks.pop() ?? "";
  for (const block of blocks) {
    let name = "message";
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) name = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    if (name !== "status" || data.length === 0) continue;
    try {
      events.push(JSON.parse(data.join("\n")) as PaymentStatusEvent);
    } catch {
      // a malformed event is ignored rather than ending the stream
    }
  }
  return { events, rest };
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const payload = (await response.json()) as {
      error?: { type?: string; code?: string; message?: string; param?: string };
    };
    const e = payload.error;
    return new ApiError(
      response.status,
      e?.type ?? "api_error",
      e?.code ?? "unknown",
      e?.message ?? response.statusText,
      e?.param,
    );
  } catch {
    return new ApiError(response.status, "api_error", "unknown", response.statusText || "Request failed");
  }
}
