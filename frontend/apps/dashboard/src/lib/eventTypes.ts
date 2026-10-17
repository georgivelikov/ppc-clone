/** The public event types, grouped the way the endpoint form shows them. Must match the webhooks service. */
export const EVENT_GROUPS: { label: string; family: string; types: string[] }[] = [
  {
    label: "Payments",
    family: "payment_intent",
    types: [
      "payment_intent.created",
      "payment_intent.requires_action",
      "payment_intent.succeeded",
      "payment_intent.payment_failed",
      "payment_intent.canceled",
    ],
  },
  { label: "Refunds", family: "refund", types: ["charge.refunded", "refund.created", "refund.failed"] },
  { label: "Payouts", family: "payout", types: ["payout.created", "payout.paid", "payout.failed"] },
];

export const ALL_EVENT_TYPES = EVENT_GROUPS.flatMap((group) => group.types);

/** A short description of what an endpoint listens to, for tables. */
export function describeEvents(enabled: string[]): string {
  if (enabled.includes("*")) return "All events";
  return enabled.length <= 2 ? enabled.join(", ") : `${enabled.slice(0, 2).join(", ")} +${enabled.length - 2} more`;
}
