// Disputes (chargebacks): a customer asks their bank for the money back (backend/services/payments).

export type DisputeStatus = "needs_response" | "under_review" | "won" | "lost";

export interface DisputeFile {
  object: "dispute_file";
  id: string;
  filename: string;
  content_type: string;
  size: number;
  uploaded_at: string;
}

export interface Dispute {
  object: "dispute";
  id: string;
  payment_intent: string;
  amount: number;
  currency: string;
  /** Charged when the dispute opens and returned if the merchant wins. */
  fee: number;
  reason: string;
  status: DisputeStatus;
  evidence_due_by: string;
  evidence_submitted_at: string | null;
  evidence_text: string | null;
  outcome_reason: string | null;
  created: string;
  closed_at: string | null;
  files: DisputeFile[];
  livemode: boolean;
}

export interface DisputeList {
  object: "list";
  data: Dispute[];
  has_more: boolean;
}

export interface DisputeListParams {
  status?: DisputeStatus;
  payment_intent?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}
