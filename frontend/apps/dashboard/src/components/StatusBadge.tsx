import type { PaymentStatus } from "@cutpay/api-client";
import { Badge } from "@cutpay/ui";

const TONE: Record<PaymentStatus, "neutral" | "success" | "warning" | "danger"> = {
  requires_payment_method: "neutral",
  processing: "warning",
  requires_action: "warning",
  requires_capture: "warning",
  succeeded: "success",
  failed: "danger",
  canceled: "neutral",
  partially_refunded: "warning",
  refunded: "neutral",
};

export const STATUS_LABEL: Record<PaymentStatus, string> = {
  requires_payment_method: "Incomplete",
  processing: "Processing",
  requires_action: "Needs authentication",
  requires_capture: "Uncaptured",
  succeeded: "Succeeded",
  failed: "Failed",
  canceled: "Canceled",
  partially_refunded: "Partially refunded",
  refunded: "Refunded",
};

export function StatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge tone={TONE[status]}>{STATUS_LABEL[status]}</Badge>;
}
