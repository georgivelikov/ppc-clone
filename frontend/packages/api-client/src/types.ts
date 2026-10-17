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
