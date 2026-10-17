// Mirrors the identity service's JSON (snake_case, see backend/services/identity).
// Phase 3 replaces these hand-written types with ones generated from the OpenAPI spec.

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
  description: string | null;
  customer_email: string | null;
  metadata: Record<string, string>;
  client_secret: string;
  return_url: string | null;
  amount_refunded: number;
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
