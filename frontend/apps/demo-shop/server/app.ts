// The demo shop's backend. It plays the part of a real merchant's server:
//  - it holds the SECRET key (never sent to the browser) and creates payments with it;
//  - it prices every order itself;
//  - it learns that an order was paid ONLY from a signed webhook, never from what the browser says.
import { randomBytes } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { PRODUCTS } from "./products.ts";
import { verify } from "./signature.ts";

export interface Config {
  /** Where the Cutpay API lives (the gateway). */
  apiUrl: string;
  /** Where the hosted checkout and the SDK live. */
  checkoutUrl: string;
  /** This shop's own public address, used for the return URL. */
  shopUrl: string;
  /** The URL to register in Cutpay for webhooks. */
  webhookUrl: string;
  secretKey?: string;
  publishableKey?: string;
  webhookSecret?: string;
  /** The setup endpoint stores keys in memory so a demo can be configured from the browser. Off by default. */
  allowSetup: boolean;
}

export type OrderStatus = "pending" | "paid" | "failed";
export type PayMode = "embedded" | "hosted";

export interface OrderLine {
  productId: string;
  name: string;
  quantity: number;
  unitAmount: number;
}

export interface Order {
  id: string;
  lines: OrderLine[];
  amount: number;
  currency: string;
  email: string | null;
  mode: PayMode;
  status: OrderStatus;
  paymentId: string;
  clientSecret: string;
  createdAt: string;
  paidAt: string | null;
  failureReason: string | null;
}

export type FetchLike = (url: string, init: { method: string; headers: Record<string, string>; body: string }) => Promise<{
  ok: boolean;
  status: number;
  json(): Promise<unknown>;
}>;

const MAX_BODY = 1_000_000;

class HttpError extends Error {
  readonly status: number;
  readonly code: string;

  // Plain fields, not parameter properties: Node strips types but cannot run TypeScript-only syntax.
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function createApp(config: Config, fetchImpl: FetchLike = fetch as unknown as FetchLike, now: () => number = Date.now) {
  const orders = new Map<string, Order>();
  const handledEvents = new Set<string>();

  /** What the browser may see of an order. The client secret is only needed to pay, so only unpaid orders show it. */
  function view(order: Order) {
    const { clientSecret, ...rest } = order;
    return order.status === "pending" && order.mode === "embedded" ? { ...rest, clientSecret } : rest;
  }

  async function readBody(req: IncomingMessage): Promise<string> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req) {
      size += (chunk as Buffer).length;
      if (size > MAX_BODY) throw new HttpError(413, "body_too_large", "The request body is too large");
      chunks.push(chunk as Buffer);
    }
    return Buffer.concat(chunks).toString("utf8");
  }

  function parseJson<T>(raw: string): T {
    try {
      return JSON.parse(raw) as T;
    } catch {
      throw new HttpError(400, "invalid_json", "The request body is not valid JSON");
    }
  }

  async function createPayment(order: Omit<Order, "paymentId" | "clientSecret">): Promise<{ paymentId: string; clientSecret: string; url?: string }> {
    if (!config.secretKey) throw new HttpError(409, "not_configured", "Add your Cutpay keys on the Settings page first");
    const hosted = order.mode === "hosted";
    const response = await fetchImpl(`${config.apiUrl}${hosted ? "/v1/checkout_sessions" : "/v1/payment_intents"}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.secretKey}`,
        "Content-Type": "application/json",
        // Retrying order creation (a double click, a timeout) must not create a second payment.
        "Idempotency-Key": order.id,
      },
      body: JSON.stringify({
        amount: order.amount,
        currency: order.currency,
        description: `Order ${order.id}`,
        customer_email: order.email,
        merchant_name: "Demo Shop",
        metadata: { order_id: order.id },
        return_url: `${config.shopUrl}/thanks?order=${order.id}`,
      }),
    });
    const body = (await response.json()) as Record<string, string> & { error?: { message?: string } };
    if (!response.ok) {
      throw new HttpError(502, "cutpay_error", body.error?.message ?? "Cutpay rejected the payment");
    }
    return hosted
      ? { paymentId: body.payment_id!, clientSecret: body.client_secret!, url: body.url }
      : { paymentId: body.id!, clientSecret: body.client_secret! };
  }

  async function createOrder(raw: string) {
    const input = parseJson<{ items?: { productId?: string; quantity?: number }[]; mode?: string; email?: string }>(raw);
    if (input.mode !== "embedded" && input.mode !== "hosted") {
      throw new HttpError(400, "invalid_mode", "mode must be embedded or hosted");
    }
    if (!Array.isArray(input.items) || input.items.length === 0) {
      throw new HttpError(400, "empty_cart", "Add something to the cart first");
    }
    const lines: OrderLine[] = input.items.map((item) => {
      const product = PRODUCTS.find((p) => p.id === item.productId);
      const quantity = item.quantity;
      if (!product) throw new HttpError(400, "unknown_product", `Unknown product ${String(item.productId)}`);
      if (!Number.isInteger(quantity) || quantity! < 1 || quantity! > 10) {
        throw new HttpError(400, "invalid_quantity", "Quantity must be between 1 and 10");
      }
      return { productId: product.id, name: product.name, quantity: quantity!, unitAmount: product.unitAmount };
    });
    const id = `ord_${randomBytes(6).toString("hex")}`;
    const draft = {
      id,
      lines,
      amount: lines.reduce((sum, line) => sum + line.unitAmount * line.quantity, 0),
      currency: "EUR",
      email: input.email?.trim() || null,
      mode: input.mode as PayMode,
      status: "pending" as OrderStatus,
      createdAt: new Date(now()).toISOString(),
      paidAt: null,
      failureReason: null,
    };
    const payment = await createPayment(draft);
    const order: Order = { ...draft, paymentId: payment.paymentId, clientSecret: payment.clientSecret };
    orders.set(id, order);
    return { ...view(order), url: payment.url };
  }

  /** The only place an order becomes paid. */
  function handleWebhook(raw: string, signature: string | undefined) {
    if (!config.webhookSecret) throw new HttpError(503, "not_configured", "No webhook secret is set");
    const result = verify(config.webhookSecret, raw, signature, Math.floor(now() / 1000));
    if (result !== "valid") throw new HttpError(400, "invalid_signature", `Webhook rejected: ${result}`);

    const event = parseJson<{ id: string; type: string; data?: { object?: { id?: string; amount?: number; failure_code?: string } } }>(raw);
    // Cutpay delivers at least once, so the same event can arrive again. Handling it twice must be harmless.
    if (handledEvents.has(event.id)) return { received: true, duplicate: true };
    handledEvents.add(event.id);

    const payment = event.data?.object;
    const order = [...orders.values()].find((candidate) => candidate.paymentId === payment?.id);
    if (!order) return { received: true, ignored: true };

    if (event.type === "payment_intent.succeeded") {
      // Never trust the event for more than it proves: check it is for the amount this order was priced at.
      if (payment?.amount !== order.amount) {
        order.status = "failed";
        order.failureReason = "Paid amount did not match the order";
      } else {
        order.status = "paid";
        order.paidAt = new Date(now()).toISOString();
      }
    } else if (event.type === "payment_intent.payment_failed" && order.status === "pending") {
      order.status = "failed";
      order.failureReason = payment?.failure_code ?? "payment_failed";
    }
    return { received: true };
  }

  const json = (res: ServerResponse, status: number, body: unknown) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };

  async function route(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url ?? "/", "http://shop.local");
    const path = url.pathname;
    const method = req.method ?? "GET";

    if (method === "GET" && path === "/api/products") return json(res, 200, { data: PRODUCTS });

    if (method === "GET" && path === "/api/config") {
      return json(res, 200, {
        configured: Boolean(config.secretKey && config.publishableKey),
        webhookConfigured: Boolean(config.webhookSecret),
        publishableKey: config.publishableKey ?? null,
        checkoutUrl: config.checkoutUrl,
        webhookUrl: config.webhookUrl,
        allowSetup: config.allowSetup,
      });
    }

    if (method === "POST" && path === "/api/setup") {
      if (!config.allowSetup) throw new HttpError(404, "not_found", "Not found");
      const input = parseJson<{ secretKey?: string; publishableKey?: string; webhookSecret?: string }>(await readBody(req));
      if (input.secretKey !== undefined && !/^sk_(test|live)_/.test(input.secretKey)) {
        throw new HttpError(400, "invalid_key", "That is not a secret key (sk_…)");
      }
      if (input.publishableKey !== undefined && !/^pk_(test|live)_/.test(input.publishableKey)) {
        throw new HttpError(400, "invalid_key", "That is not a publishable key (pk_…)");
      }
      if (input.secretKey !== undefined) config.secretKey = input.secretKey;
      if (input.publishableKey !== undefined) config.publishableKey = input.publishableKey;
      if (input.webhookSecret !== undefined) config.webhookSecret = input.webhookSecret;
      return json(res, 200, { configured: Boolean(config.secretKey && config.publishableKey) });
    }

    if (method === "POST" && path === "/api/orders") return json(res, 201, await createOrder(await readBody(req)));

    if (method === "GET" && path === "/api/orders") {
      return json(res, 200, { data: [...orders.values()].reverse().map(view) });
    }

    const single = /^\/api\/orders\/([\w-]+)$/.exec(path);
    if (method === "GET" && single) {
      const order = orders.get(single[1]!);
      if (!order) throw new HttpError(404, "order_not_found", "No such order");
      return json(res, 200, view(order));
    }

    if (method === "POST" && path === "/webhooks/cutpay") {
      const raw = await readBody(req);
      return json(res, 200, handleWebhook(raw, req.headers["cutpay-signature"] as string | undefined));
    }

    throw new HttpError(404, "not_found", "Not found");
  }

  return {
    handler(req: IncomingMessage, res: ServerResponse) {
      route(req, res).catch((error: unknown) => {
        if (error instanceof HttpError) {
          json(res, error.status, { error: { code: error.code, message: error.message } });
        } else {
          console.error(error);
          json(res, 500, { error: { code: "server_error", message: "Something went wrong" } });
        }
      });
    },
    /** For tests. */
    orders,
  };
}
