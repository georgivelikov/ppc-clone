// Types for customers, saved cards and setup intents (backend/services/payments).

export interface Address {
  line1?: string | null;
  line2?: string | null;
  city?: string | null;
  postal_code?: string | null;
  country?: string | null;
}

export interface Customer {
  id: string;
  object: "customer";
  livemode: boolean;
  email: string | null;
  name: string | null;
  phone: string | null;
  description: string | null;
  address: Address | null;
  metadata: Record<string, string>;
  /** The saved card used when nothing else is chosen, e.g. for a subscription. */
  default_payment_method: string | null;
  created_at: string;
}

export interface CustomerList {
  object: "list";
  data: Customer[];
  has_more: boolean;
}

export interface CustomerListParams {
  q?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface CreateCustomerInput {
  email?: string;
  name?: string;
  phone?: string;
  description?: string;
  address?: Address;
  metadata?: Record<string, string>;
}

export type UpdateCustomerInput = CreateCustomerInput & {
  default_payment_method?: string;
};

/** Only brand, last four digits and expiry are ever returned; the number lives in the card vault. */
export interface PaymentMethod {
  id: string;
  object: "payment_method";
  type: "card";
  customer: string;
  livemode: boolean;
  card: {
    brand: string | null;
    last4: string | null;
    exp_month: number | null;
    exp_year: number | null;
    fingerprint: string | null;
  };
  is_default: boolean;
  created_at: string;
}

export interface PaymentMethodList {
  object: "list";
  data: PaymentMethod[];
}

export type SetupIntentStatus = "requires_payment_method" | "requires_action" | "succeeded" | "failed" | "canceled";

/** A request to save a customer's card without charging it. The customer enters the card on the hosted page. */
export interface SetupIntent {
  id: string;
  object: "setup_intent";
  livemode: boolean;
  customer: string;
  status: SetupIntentStatus;
  client_secret: string;
  /** The hosted page where the customer enters their card. */
  url: string;
  payment_method: string | null;
  description: string | null;
  return_url: string | null;
  failure_code: string | null;
  failure_message: string | null;
  created_at: string;
}

export interface CreateSetupIntentInput {
  customer: string;
  description?: string;
  return_url?: string;
  /** Shown to the customer on the hosted page. */
  merchant_name?: string;
}
