import type { InvoiceStatus, SubscriptionStatus } from "@cutpay/api-client";
import { Badge } from "@cutpay/ui";

type Tone = "neutral" | "success" | "warning" | "danger";

const SUBSCRIPTION_TONE: Record<SubscriptionStatus, Tone> = {
  trialing: "neutral",
  active: "success",
  past_due: "warning",
  canceled: "neutral",
  unpaid: "danger",
  incomplete: "warning",
};

export const SUBSCRIPTION_LABEL: Record<SubscriptionStatus, string> = {
  trialing: "Trial",
  active: "Active",
  past_due: "Past due",
  canceled: "Canceled",
  unpaid: "Unpaid",
  incomplete: "Incomplete",
};

export function SubscriptionStatusBadge({ status }: { status: SubscriptionStatus }) {
  return <Badge tone={SUBSCRIPTION_TONE[status]}>{SUBSCRIPTION_LABEL[status]}</Badge>;
}

const INVOICE_TONE: Record<InvoiceStatus, Tone> = {
  draft: "neutral",
  open: "warning",
  paid: "success",
  void: "neutral",
  uncollectible: "danger",
};

export const INVOICE_LABEL: Record<InvoiceStatus, string> = {
  draft: "Draft",
  open: "Open",
  paid: "Paid",
  void: "Void",
  uncollectible: "Uncollectible",
};

export function InvoiceStatusBadge({ status }: { status: InvoiceStatus }) {
  return <Badge tone={INVOICE_TONE[status]}>{INVOICE_LABEL[status]}</Badge>;
}
