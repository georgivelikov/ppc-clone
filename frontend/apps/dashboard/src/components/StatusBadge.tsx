import type { PaymentStatus } from "@cutpay/api-client";
import { useTranslation } from "@cutpay/i18n";
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

/** The statuses, in the order the filter lists them. The words for them are in the messages: `status.<status>`. */
export const PAYMENT_STATUSES = Object.keys(TONE) as PaymentStatus[];

export function StatusBadge({ status }: { status: PaymentStatus }) {
  const { t } = useTranslation();
  return <Badge tone={TONE[status]}>{t(`status.${status}`)}</Badge>;
}
