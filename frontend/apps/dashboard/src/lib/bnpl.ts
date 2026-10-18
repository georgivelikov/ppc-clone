import type { BnplApplication, RoutingMode } from "@cutpay/api-client";
import { formatMoney } from "@cutpay/ui";

export const ROUTING_MODES: { value: RoutingMode; label: string; help: string }[] = [
  { value: "customer_choice", label: "The customer chooses", help: "Customers pick from your providers, in the order below." },
  { value: "cheapest", label: "Cheapest for me", help: "Cutpay recommends the provider that keeps the least." },
  { value: "fastest", label: "Fastest to decide", help: "Cutpay recommends the provider that usually answers soonest." },
  { value: "best_approval", label: "Most likely to approve", help: "Cutpay recommends the provider that approves most customers." },
  { value: "weighted", label: "A mix, by weights", help: "Cost, speed and approval rate count as much as you say below." },
];

/** "0.3 s", "12 s", "1.5 min": how long a provider took, or a dash when it has not decided anything. */
export function durationText(ms: number): string {
  if (ms <= 0) return "—";
  if (ms < 1000) return `${ms} ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${Number(seconds.toFixed(1))} s`;
  return `${Number((seconds / 60).toFixed(1))} min`;
}

/** 0.857 as "86%", or a dash for a provider that has not decided anything yet. */
export function percentText(rate: number | null): string {
  return rate === null ? "—" : `${Math.round(rate * 100)}%`;
}

/** "3 × €100.00", or "€300.00 in 30 days" for a plan with one payment. */
export function planLabel(application: Pick<BnplApplication, "instalments" | "instalment_amounts" | "interval_days" | "currency">): string {
  const first = application.instalment_amounts[0] ?? 0;
  return application.instalments === 1
    ? `${formatMoney(first, application.currency)} in ${application.interval_days} days`
    : `${application.instalments} × ${formatMoney(first, application.currency)}`;
}
