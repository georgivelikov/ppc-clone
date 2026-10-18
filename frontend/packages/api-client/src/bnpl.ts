// Types for pay in instalments (backend/services/bnpl): settings, applications, routing decisions and analytics.

export type RoutingMode = "customer_choice" | "cheapest" | "fastest" | "best_approval" | "weighted";

export interface RoutingWeights {
  cost: number;
  speed: number;
  approval: number;
}

export interface BnplProvider {
  code: string;
  name: string;
  enabled: boolean;
  /** The position of the provider in the merchant's order, 0 first. */
  priority: number;
  min_amount: number;
  max_amount: number;
  currencies: string[];
  countries: string[];
}

export interface BnplSettings {
  object: "bnpl_settings";
  routing_mode: RoutingMode;
  weights: RoutingWeights;
  /** In the order the merchant prefers them. */
  providers: BnplProvider[];
}

export interface UpdateBnplSettingsInput {
  routing_mode?: RoutingMode;
  weights?: RoutingWeights;
  /** A priority is a position: the provider takes exactly that place and the others keep their order around it. */
  providers?: { code: string; enabled?: boolean; priority?: number }[];
}

export type BnplApplicationStatus =
  | "created"
  | "pending_review"
  | "approved"
  | "declined"
  | "failed"
  | "expired"
  | "canceled"
  | "settled";

export interface BnplApplication {
  id: string;
  object: "bnpl_application";
  livemode: boolean;
  payment: string;
  provider: string;
  provider_name: string;
  plan: string;
  status: BnplApplicationStatus;
  provider_ref: string | null;
  amount: number;
  currency: string;
  country: string;
  instalments: number;
  instalment_amounts: number[];
  interval_days: number;
  apr: number;
  total_payable: number;
  /** What the provider keeps. */
  merchant_fee: number;
  /** What the merchant receives: the price less the fee. */
  net_amount: number;
  customer_email: string | null;
  decline_reason: string | null;
  decision_ms: number | null;
  expires_at: string;
  created_at: string;
  decided_at: string | null;
}

export interface BnplApplicationList {
  object: "list";
  data: BnplApplication[];
  has_more: boolean;
}

export interface BnplApplicationListParams {
  status?: BnplApplicationStatus | "";
  provider?: string;
  payment?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface RoutingExclusion {
  provider: string;
  code: string;
  message: string;
}

export interface RoutingCandidate {
  provider: string;
  name: string;
  rank: number;
  score: number;
  best: { plan: string; merchant_fee: number; total_payable: number };
  stats: { approval_rate: number; p50_decision_ms: number };
}

/** Why a provider was chosen for a payment. */
export interface RoutingDecision {
  object: "routing_decision";
  id: string;
  payment: string;
  strategy: RoutingMode;
  amount: number;
  currency: string;
  country: string;
  chosen_provider: string | null;
  reason: string;
  ranked: RoutingCandidate[];
  excluded: RoutingExclusion[];
  created_at: string;
}

export type CircuitState = "closed" | "open" | "half_open";

export interface BnplProviderAnalytics {
  provider: string;
  name: string;
  applications: number;
  approved: number;
  declined: number;
  failed: number;
  pending: number;
  abandoned: number;
  /** Of the applications that were decided; null while there are none. */
  approval_rate: number | null;
  avg_decision_ms: number;
  p50_decision_ms: number;
  volume: number;
  fees: number;
  circuit: CircuitState;
}

export interface BnplAnalytics {
  object: "bnpl_analytics";
  days: number;
  providers: BnplProviderAnalytics[];
  applications: number;
  approved: number;
  volume: number;
  fees: number;
}
