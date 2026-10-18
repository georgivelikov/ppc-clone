// Pay in instalments, as the customer at the checkout sees it. Like the rest of the checkout there is no login: the client
// secret in the URL is the credential. The merchant's fee and the ranking of providers are never sent here.
import { call } from "./api";

export interface BnplOffer {
  provider: string;
  provider_name: string;
  /** Unique across providers, for example `splitnow_3`. */
  plan: string;
  instalments: number;
  instalment_amounts: number[];
  interval_days: number;
  apr: number;
  total_payable: number;
  /** What financing costs on top of the price. */
  customer_cost: number;
  /** What the provider needs to know about the customer to decide. */
  required_fields: BnplField[];
}

export type BnplField = "email" | "birthdate" | "national_id" | "monthly_income";

export interface BnplOffers {
  object: "bnpl_offers";
  payment: string;
  amount: number;
  currency: string;
  country: string;
  routing_mode: string;
  offers: BnplOffer[];
  recommended: { provider: string; plan: string } | null;
  /** Providers that could not be reached, so the customer knows why one is missing. */
  unavailable: { provider: string; name: string; reason: string }[];
}

export type BnplStatus = "created" | "pending_review" | "approved" | "declined" | "failed" | "expired" | "canceled" | "settled";

export interface BnplApplicationView {
  object: "bnpl_application";
  id: string;
  status: BnplStatus;
  provider: string;
  provider_name: string;
  plan: string;
  instalments: number;
  instalment_amounts: number[];
  interval_days: number;
  apr: number;
  total_payable: number;
  awaiting_decision: boolean;
  message: string;
}

export interface BnplApplicant {
  email?: string;
  name?: string;
  /** yyyy-MM-dd */
  birthdate?: string;
  national_id?: string;
  /** In minor units. */
  monthly_income?: number;
}

export interface BnplBestResult {
  object: "bnpl_best";
  application: BnplApplicationView;
  attempts: { id: string; provider: string; provider_name: string; status: BnplStatus }[];
}

const base = (secret: string) => `/checkout/${encodeURIComponent(secret)}/bnpl`;

export function getBnplOffers(secret: string): Promise<BnplOffers> {
  return call("GET", `${base(secret)}/offers`);
}

export function applyBnpl(secret: string, provider: string, plan: string, applicant: BnplApplicant): Promise<BnplApplicationView> {
  return call("POST", `${base(secret)}/apply`, { provider, plan, applicant });
}

/** Lets Cutpay pick; with `fallback` the next best providers may be tried after a decline. */
export function applyBnplBest(secret: string, applicant: BnplApplicant, fallback: boolean): Promise<BnplBestResult> {
  return call("POST", `${base(secret)}/apply_best`, { applicant, fallback });
}

export function getBnplApplication(secret: string, id: string): Promise<BnplApplicationView> {
  return call("GET", `${base(secret)}/applications/${encodeURIComponent(id)}`);
}

export function cancelBnplApplication(secret: string, id: string): Promise<BnplApplicationView> {
  return call("POST", `${base(secret)}/applications/${encodeURIComponent(id)}/cancel`);
}
