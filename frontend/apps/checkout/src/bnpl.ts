import { currentLocale, formatMoney, i18next } from "@cutpay/i18n";
import type { BnplOffer } from "./bnplApi";

const t = (key: string, options?: Record<string, unknown>): string => i18next.t(key, options) as string;
const money = (minor: number, currency: string): string => formatMoney(minor, currency, currentLocale());

/** "every 2 weeks", "every month", "every 10 days": how often the instalments fall due, in the language being shown. */
export function intervalText(days: number): string {
  if (days === 7) return t("bnpl.interval.weeks", { count: 1 });
  if (days % 30 === 0) return t("bnpl.interval.months", { count: days / 30 });
  if (days % 7 === 0) return t("bnpl.interval.weeks", { count: days / 7 });
  return t("bnpl.interval.days", { count: days });
}

/** What the customer pays and when, in one line. */
export function planText(offer: Pick<BnplOffer, "instalments" | "instalment_amounts" | "interval_days">, currency: string): string {
  if (offer.instalments === 1) {
    return t("bnpl.plan.single", { count: offer.interval_days, amount: money(offer.instalment_amounts[0] ?? 0, currency) });
  }
  const amounts = new Set(offer.instalment_amounts);
  const first = offer.instalment_amounts[0] ?? 0;
  // Instalments that differ by a cent (a price that does not divide evenly) are still "N payments of about".
  const even = amounts.size === 1;
  return t(even ? "bnpl.plan.many" : "bnpl.plan.about", {
    count: offer.instalments,
    amount: money(first, currency),
    interval: intervalText(offer.interval_days),
  });
}

/** What it costs on top of the price: nothing, or the interest with the rate. */
export function costText(offer: Pick<BnplOffer, "customer_cost" | "apr" | "total_payable">, currency: string): string {
  if (offer.customer_cost <= 0) {
    return t("bnpl.cost.none", { total: money(offer.total_payable, currency) });
  }
  return t("bnpl.cost.interest", {
    interest: money(offer.customer_cost, currency),
    apr: offer.apr,
    total: money(offer.total_payable, currency),
  });
}

/** The fields that all of these offers need between them, in the order a form asks for them. */
export function fieldsFor(offers: Pick<BnplOffer, "required_fields">[]): BnplOffer["required_fields"] {
  const order: BnplOffer["required_fields"] = ["email", "birthdate", "national_id", "monthly_income"];
  const needed = new Set(offers.flatMap((o) => o.required_fields));
  return order.filter((field) => needed.has(field));
}
