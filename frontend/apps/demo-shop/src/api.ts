// The browser talks only to the shop's own backend. Cutpay is called from there, with the secret key.

export interface Product {
  id: string;
  name: string;
  description: string;
  unitAmount: number;
  currency: string;
}

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
  mode: "embedded" | "hosted";
  status: "pending" | "paid" | "failed";
  paymentId: string;
  createdAt: string;
  paidAt: string | null;
  failureReason: string | null;
  /** Present while an embedded order is unpaid: what the card element needs. */
  clientSecret?: string;
  /** Present on the response that created a hosted order. */
  url?: string;
}

export interface ShopConfig {
  configured: boolean;
  webhookConfigured: boolean;
  publishableKey: string | null;
  checkoutUrl: string;
  webhookUrl: string;
  allowSetup: boolean;
}

export class ShopApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = (await response.json().catch(() => ({}))) as T & { error?: { code?: string; message?: string } };
  if (!response.ok) {
    throw new ShopApiError(response.status, payload.error?.code ?? "unknown", payload.error?.message ?? "Request failed");
  }
  return payload;
}

export const getProducts = () => call<{ data: Product[] }>("GET", "/api/products").then((r) => r.data);
export const getConfig = () => call<ShopConfig>("GET", "/api/config");
export const getOrders = () => call<{ data: Order[] }>("GET", "/api/orders").then((r) => r.data);
export const getOrder = (id: string) => call<Order>("GET", `/api/orders/${encodeURIComponent(id)}`);
export const createOrder = (input: { items: { productId: string; quantity: number }[]; mode: "embedded" | "hosted"; email?: string }) =>
  call<Order>("POST", "/api/orders", input);
export const saveSetup = (input: { secretKey?: string; publishableKey?: string; webhookSecret?: string }) =>
  call<{ configured: boolean }>("POST", "/api/setup", input);
