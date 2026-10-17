import type { PaymentLinkStatus } from "@cutpay/api-client";
import { Badge } from "@cutpay/ui";

const TONE: Record<PaymentLinkStatus, "neutral" | "success" | "warning"> = {
  active: "success",
  inactive: "neutral",
  expired: "warning",
  sold_out: "warning",
};

const LABEL: Record<PaymentLinkStatus, string> = {
  active: "Active",
  inactive: "Inactive",
  expired: "Expired",
  sold_out: "Fully used",
};

export function LinkStatusBadge({ status }: { status: PaymentLinkStatus }) {
  return <Badge tone={TONE[status]}>{LABEL[status]}</Badge>;
}

export const LINK_TYPE_LABEL = {
  fixed: "Fixed price",
  customer_chooses: "Customer chooses",
  products: "Products",
} as const;
