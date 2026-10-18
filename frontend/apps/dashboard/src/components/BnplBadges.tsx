import type { BnplApplicationStatus, CircuitState } from "@cutpay/api-client";
import { Badge } from "@cutpay/ui";

type Tone = "neutral" | "success" | "warning" | "danger";

const STATUS_TONE: Record<BnplApplicationStatus, Tone> = {
  created: "warning",
  pending_review: "warning",
  approved: "success",
  settled: "success",
  declined: "danger",
  failed: "danger",
  expired: "neutral",
  canceled: "neutral",
};

export const BNPL_STATUS_LABEL: Record<BnplApplicationStatus, string> = {
  created: "Sent",
  pending_review: "Waiting for the provider",
  approved: "Approved",
  settled: "Paid by the provider",
  declined: "Declined",
  failed: "Provider unavailable",
  expired: "Expired",
  canceled: "Canceled",
};

export function BnplStatusBadge({ status }: { status: BnplApplicationStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{BNPL_STATUS_LABEL[status]}</Badge>;
}

const CIRCUIT: Record<CircuitState, { tone: Tone; label: string }> = {
  closed: { tone: "success", label: "Working" },
  half_open: { tone: "warning", label: "Checking" },
  open: { tone: "danger", label: "Down" },
};

/** Whether the aggregator is currently sending customers to a provider. */
export function CircuitBadge({ state }: { state: CircuitState }) {
  const { tone, label } = CIRCUIT[state];
  return <Badge tone={tone}>{label}</Badge>;
}
