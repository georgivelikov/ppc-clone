import type { PayoutStatus } from "@cutpay/api-client";
import { Badge } from "@cutpay/ui";

const TONE: Record<PayoutStatus, "neutral" | "success" | "warning" | "danger"> = {
  pending: "neutral",
  in_transit: "warning",
  paid: "success",
  failed: "danger",
};

export const PAYOUT_STATUS_LABEL: Record<PayoutStatus, string> = {
  pending: "Pending",
  in_transit: "In transit",
  paid: "Paid",
  failed: "Failed",
};

export function PayoutStatusBadge({ status }: { status: PayoutStatus }) {
  return <Badge tone={TONE[status]}>{PAYOUT_STATUS_LABEL[status]}</Badge>;
}

/** Plain-language reasons for the failure codes the fake bank can return. */
export const PAYOUT_FAILURE_LABEL: Record<string, string> = {
  account_closed: "The bank account is closed.",
};
