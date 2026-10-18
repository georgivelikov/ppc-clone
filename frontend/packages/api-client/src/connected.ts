// Types for connected accounts, the sub-merchants of a platform (backend/services/identity).

export interface ConnectedAccount {
  id: string;
  object: "connected_account";
  livemode: boolean;
  name: string;
  email: string | null;
  country: string;
  /** What it receives and is paid out in: the default currency of the platform. */
  currency: string;
  iban_last4: string;
  status: "active" | "disabled";
  created_at: string;
}

export interface ConnectedAccountList {
  object: "list";
  data: ConnectedAccount[];
  has_more: boolean;
}

export interface CreateConnectedAccountInput {
  name: string;
  email?: string;
  iban: string;
}

export interface UpdateConnectedAccountInput {
  name?: string;
  email?: string;
  iban?: string;
}

/** Part of a payment sent to a connected account. */
export interface TransferData {
  destination: string;
  /** Leave it out for a single destination together with `application_fee_amount`. */
  amount?: number;
}
