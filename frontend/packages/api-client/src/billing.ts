// Types for products and prices, subscriptions and invoices (backend/services/billing).

export type PriceInterval = "day" | "week" | "month" | "year";

export interface Product {
  id: string;
  object: "product";
  livemode: boolean;
  name: string;
  description: string | null;
  image_url: string | null;
  active: boolean;
  metadata: Record<string, string>;
  created_at: string;
}

export interface ProductList {
  object: "list";
  data: Product[];
  has_more: boolean;
}

export interface CreateProductInput {
  name: string;
  description?: string;
  image_url?: string;
}

export interface UpdateProductInput {
  name?: string;
  description?: string;
  active?: boolean;
}

/** A price never changes once created, so a subscription keeps the terms it signed up for. */
export interface Price {
  id: string;
  object: "price";
  livemode: boolean;
  product: string;
  unit_amount: number;
  currency: string;
  type: "one_time" | "recurring";
  interval: PriceInterval | null;
  interval_count: number;
  trial_days: number;
  nickname: string | null;
  active: boolean;
  created_at: string;
}

export interface PriceList {
  object: "list";
  data: Price[];
  has_more: boolean;
}

export interface CreatePriceInput {
  product: string;
  unit_amount: number;
  currency: string;
  interval?: PriceInterval;
  interval_count?: number;
  trial_days?: number;
  nickname?: string;
}

export type SubscriptionStatus = "trialing" | "active" | "past_due" | "canceled" | "unpaid" | "incomplete";

export interface SubscriptionItem {
  id: string;
  price: string;
  product: string;
  product_name: string;
  nickname: string | null;
  unit_amount: number;
  quantity: number;
  amount: number;
}

export interface Subscription {
  id: string;
  object: "subscription";
  livemode: boolean;
  customer: string;
  customer_email: string | null;
  customer_name: string | null;
  status: SubscriptionStatus;
  currency: string;
  interval: PriceInterval;
  interval_count: number;
  collection_method: "charge_automatically" | "send_invoice";
  default_payment_method: string | null;
  current_period_start: string;
  current_period_end: string;
  trial_end: string | null;
  cancel_at_period_end: boolean;
  collection_paused: boolean;
  canceled_at: string | null;
  ended_at: string | null;
  tax_rate_percent: number;
  latest_invoice: string | null;
  metadata: Record<string, string>;
  items: SubscriptionItem[];
  created_at: string;
}

export interface SubscriptionList {
  object: "list";
  data: Subscription[];
  has_more: boolean;
}

export interface SubscriptionListParams {
  status?: SubscriptionStatus | "";
  customer?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface SubscriptionItemInput {
  price: string;
  quantity: number;
}

export interface CreateSubscriptionInput {
  customer: string;
  items: SubscriptionItemInput[];
  trial_days?: number;
  collection_method?: "charge_automatically" | "send_invoice";
  default_payment_method?: string;
  tax_rate_percent?: number;
  merchant_name?: string;
}

export interface UpdateSubscriptionInput {
  items?: SubscriptionItemInput[];
  cancel_at_period_end?: boolean;
  default_payment_method?: string;
  proration_behavior?: "create_prorations" | "none";
  bill_immediately?: boolean;
}

export interface ProrationPreview {
  currency: string;
  period_end: string;
  /** Negative is a credit to the customer. */
  net: number;
  lines: { description: string; unit_amount: number; quantity: number; amount: number }[];
}

export interface SubscriptionStats {
  object: "subscription_stats";
  /** Monthly recurring revenue per currency, in minor units. */
  mrr: Record<string, number>;
  by_status: Partial<Record<SubscriptionStatus, number>>;
  ended_last_30_days: number;
}

export type InvoiceStatus = "draft" | "open" | "paid" | "void" | "uncollectible";

export interface InvoiceLine {
  id: string;
  description: string;
  unit_amount: number;
  quantity: number;
  amount: number;
  price: string | null;
  proration: boolean;
  period_start: string | null;
  period_end: string | null;
}

export interface InvoiceAttempt {
  attempt_no: number;
  scheduled_at: string;
  executed_at: string | null;
  result: string | null;
}

export interface Invoice {
  id: string;
  object: "invoice";
  livemode: boolean;
  /** Set when the invoice is finalized, for example INV-0001. */
  number: string | null;
  status: InvoiceStatus;
  subscription: string | null;
  customer: string;
  customer_email: string | null;
  customer_name: string | null;
  billing_reason: "manual" | "subscription_create" | "subscription_cycle" | "subscription_update";
  collection_method: "charge_automatically" | "send_invoice";
  currency: string;
  subtotal: number;
  tax: number;
  total: number;
  amount_paid: number;
  amount_due: number;
  description: string | null;
  due_date: string | null;
  period_start: string | null;
  period_end: string | null;
  payment_intent: string | null;
  failure_code: string | null;
  attempt_count: number;
  next_payment_attempt: string | null;
  hosted_invoice_url: string | null;
  invoice_pdf: string | null;
  lines: InvoiceLine[];
  /** Only on a single invoice. */
  attempts: InvoiceAttempt[] | null;
  created_at: string;
  finalized_at: string | null;
  paid_at: string | null;
}

export interface InvoiceList {
  object: "list";
  data: Invoice[];
  has_more: boolean;
}

export interface InvoiceListParams {
  status?: InvoiceStatus | "";
  customer?: string;
  subscription?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface CreateInvoiceInput {
  customer: string;
  currency: string;
  lines: { description: string; unit_amount: number; quantity: number }[];
  collection_method?: "charge_automatically" | "send_invoice";
  days_until_due?: number;
  tax_rate_percent?: number;
  description?: string;
  merchant_name?: string;
}

export interface PayInvoiceResult {
  invoice: Invoice;
  paid: boolean;
  failure_code: string | null;
  failure_message: string | null;
}

export interface UpcomingInvoice {
  object: "upcoming_invoice";
  currency: string;
  period_start: string;
  period_end: string;
  lines: InvoiceLine[];
  subtotal: number;
  tax: number;
  total: number;
}

export interface BillingSettings {
  object: "billing_settings";
  retry_days: number[];
  final_action: "cancel" | "unpaid";
}

/** An email the platform sent (or would have) about a subscription or an invoice. */
export interface BillingNotification {
  id: string;
  object: "notification";
  kind: string;
  recipient: string | null;
  subject: string;
  body: string;
  related_id: string | null;
  created_at: string;
}
