// Mirrors the identity service's JSON (snake_case, see backend/services/identity).
// Phase 3 replaces these hand-written types with ones generated from the OpenAPI spec.
import type { TransferData } from "./connected";

export type Role = "owner" | "admin" | "developer" | "viewer";

export interface User {
  id: string;
  email: string;
  full_name: string;
}

export interface Merchant {
  id: string;
  display_name: string;
  legal_name: string;
  country: string;
  default_currency: string;
  status: "pending_kyc" | "active" | "suspended";
}

export interface AuthResponse {
  access_token: string;
  token_type: "Bearer";
  expires_in: number;
  user: User;
  merchant: Merchant;
  role: Role;
}

export interface Membership {
  merchant_id: string;
  merchant_name: string;
  role: Role;
}

export interface MeResponse {
  user: User;
  memberships: Membership[];
}

export interface SignupInput {
  email: string;
  password: string;
  full_name: string;
  business_name: string;
  country: string;
}

export interface LoginInput {
  email: string;
  password: string;
  merchant_id?: string;
}

export interface UpdateMerchantInput {
  display_name?: string;
  default_currency?: string;
}

// --- payments (backend/services/payments) -----------------------------------------------------

export type PaymentStatus =
  | "requires_payment_method"
  | "processing"
  | "requires_action"
  | "requires_capture"
  | "succeeded"
  | "failed"
  | "canceled"
  | "partially_refunded"
  | "refunded";

export interface PaymentAttempt {
  id: string;
  method_type: string;
  card_brand: string | null;
  card_last4: string | null;
  card_exp_month: number | null;
  card_exp_year: number | null;
  status: string;
  decline_code: string | null;
  three_ds_status: string | null;
  created_at: string;
}

export interface Refund {
  id: string;
  payment_intent: string;
  amount: number;
  currency: string;
  reason: string | null;
  status: "pending" | "succeeded" | "failed";
  failure_code: string | null;
  created_at: string;
}

export interface Payment {
  id: string;
  object: "payment_intent";
  livemode: boolean;
  amount: number;
  currency: string;
  status: PaymentStatus;
  capture_method: string;
  /** "online" for checkout and the API, "moto" for orders keyed in at the virtual terminal. */
  channel: "online" | "moto";
  description: string | null;
  customer_email: string | null;
  metadata: Record<string, string>;
  client_secret: string;
  return_url: string | null;
  amount_refunded: number;
  /** What was held on the card for a manual-capture payment; `amount` is what was taken once captured. */
  amount_authorized: number | null;
  /** While `requires_capture`: when the hold is released automatically. */
  capture_before: string | null;
  /** The customer this payment belongs to, when it was made for a saved customer. */
  customer: string | null;
  payment_method: string | null;
  /** Charged without the customer present, using a saved card. */
  off_session: boolean;
  /** How the payment was settled: by card, or financed in instalments by a provider. */
  payment_method_type?: "card" | "apple_pay" | "google_pay" | "blik" | "bnpl";
  /** When financed in instalments: who, which application, and what the provider kept. */
  bnpl?: { provider: string; application: string; fee: number } | null;
  /** What the platform keeps of a split payment, when it said so. */
  application_fee_amount?: number | null;
  /** How the payment is shared with connected accounts. Only on a single payment. */
  transfer_data?: { destination: string; amount: number }[] | null;
  failure_code: string | null;
  failure_message: string | null;
  created_at: string;
  updated_at: string;
  attempts: PaymentAttempt[];
  refunds: Refund[];
}

export interface PaymentList {
  data: Payment[];
  has_more: boolean;
}

export interface PaymentListParams {
  status?: PaymentStatus | "";
  q?: string;
  livemode?: boolean;
  /** Only the payments of this customer. */
  customer?: string;
  starting_after?: string;
  limit?: number;
}

export interface CreatePaymentInput {
  amount: number;
  currency: string;
  description?: string;
  customer_email?: string;
  return_url?: string;
  merchant_name?: string;
  /** `manual` only holds the amount; capture it later with `capturePayment`. */
  capture_method?: "automatic" | "manual";
  customer?: string;
  /** A saved card of the customer. */
  payment_method?: string;
  /** Charge the saved card right away without the customer present (subscriptions, repeat orders). */
  off_session?: boolean;
  /** `off_session` saves the card used at checkout to the customer for future payments. */
  setup_future_usage?: "off_session";
  /** Share the payment with connected accounts once it succeeds. */
  transfer_data?: TransferData[];
  /** What the platform keeps, for a single destination without an amount. */
  application_fee_amount?: number;
}

export interface CheckoutSessionCreated {
  payment_id: string;
  client_secret: string;
  url: string;
}

export interface CreateRefundInput {
  payment_intent: string;
  /** Minor units; omit to refund everything that is left. */
  amount?: number;
  reason?: string;
}

export interface PaymentSummary {
  since: string;
  today: { currency: string; count: number; volume: number }[];
}

// --- balances and payouts (backend/services/ledger, backend/services/payouts) ---------------------

export interface MoneyAmount {
  /** Minor units. Negative means the merchant owes money (e.g. a refund after a payout). */
  amount: number;
  currency: string;
}

export interface Balance {
  object: "balance";
  livemode: boolean;
  pending: MoneyAmount[];
  available: MoneyAmount[];
  reserved: MoneyAmount[];
}

export type BalanceTransactionType =
  | "payment"
  | "refund"
  | "payout"
  | "payout_failure"
  | "transfer"
  | "transfer_reversal"
  | "conversion";

export interface BalanceTransaction {
  id: string;
  object: "balance_transaction";
  type: BalanceTransactionType;
  /** The payment, refund or payout this row is about. */
  source_id: string;
  /** For refunds: the payment that was refunded. */
  parent_id: string | null;
  currency: string;
  /** Before fees. */
  gross: number;
  fee: number;
  /** What the merchant's balance changed by. */
  net: number;
  description: string;
  available_on: string | null;
  created: string;
  livemode: boolean;
}

export interface BalanceTransactionList {
  object: "list";
  data: BalanceTransaction[];
  has_more: boolean;
}

export interface BalanceTransactionListParams {
  /** Read the transactions of one of your connected accounts. */
  connected_account?: string;
  type?: BalanceTransactionType | "";
  /** A payment id also matches the refunds made against it. */
  source?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface BalanceSummaryDay {
  date: string;
  currency: string;
  /** Card volume in minor units. */
  volume: number;
  fees: number;
  net: number;
}

export interface BalanceSummary {
  object: "list";
  data: BalanceSummaryDay[];
}

export type PayoutStatus = "pending" | "in_transit" | "paid" | "failed";

export interface Payout {
  id: string;
  object: "payout";
  /** What reaches the bank, in minor units. */
  amount: number;
  /** Instant-payout fee, charged on top of the amount. */
  fee: number;
  currency: string;
  status: PayoutStatus;
  kind: "scheduled" | "instant";
  arrival_date: string;
  bank_account_id: string;
  failure_code: string | null;
  created: string;
  livemode: boolean;
}

export interface PayoutItem {
  journal_entry_ref: string;
  /** The payment, refund or payout the ledger entry came from. */
  source_id: string;
  amount: number;
}

export interface PayoutDetail {
  payout: Payout;
  items: PayoutItem[];
}

export interface PayoutList {
  object: "list";
  data: Payout[];
  has_more: boolean;
}

export interface PayoutListParams {
  status?: PayoutStatus | "";
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

/** What "Pay out now" would do right now. */
export interface PayoutPreview {
  currency: string;
  available: number;
  fee: number;
  net: number;
  has_bank_account: boolean;
}

export interface PayoutReconciliation {
  payout_id: string;
  amount: number;
  fee: number;
  items_total: number;
  item_count: number;
  /** True when the items add up to amount plus fee. */
  balanced: boolean;
}

export type PayoutScheduleInterval = "daily" | "weekly" | "manual";

export interface PayoutSchedule {
  object: "payout_schedule";
  schedule: PayoutScheduleInterval;
  /** Days funds stay pending after a payment. */
  delay_days: number;
  weekly_anchor: string;
}

export interface UpdatePayoutScheduleInput {
  schedule?: PayoutScheduleInterval;
  delay_days?: number;
  weekly_anchor?: string;
}

export interface BankAccount {
  id: string;
  object: "bank_account";
  country: string;
  iban_last4: string;
  holder_name: string;
  currency: string;
  status: "active" | "removed";
  livemode: boolean;
  created: string;
}

export interface BankAccountList {
  object: "list";
  data: BankAccount[];
}

export interface CreateBankAccountInput {
  iban: string;
  holder_name: string;
  currency?: string;
}
