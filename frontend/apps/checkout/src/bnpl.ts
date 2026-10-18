import { formatMoney } from "@cutpay/ui";
import type { BnplOffer } from "./bnplApi";

/** "every 2 weeks", "every month", "every 10 days": how often the instalments fall due. */
export function intervalText(days: number): string {
  if (days === 7) return "every week";
  if (days % 30 === 0) return days === 30 ? "every month" : `every ${days / 30} months`;
  if (days % 7 === 0) return `every ${days / 7} weeks`;
  return `every ${days} days`;
}

/** What the customer pays and when, in one line. */
export function planText(offer: Pick<BnplOffer, "instalments" | "instalment_amounts" | "interval_days">, currency: string): string {
  if (offer.instalments === 1) {
    return `${formatMoney(offer.instalment_amounts[0] ?? 0, currency)} in ${offer.interval_days} days`;
  }
  const amounts = new Set(offer.instalment_amounts);
  const first = offer.instalment_amounts[0] ?? 0;
  // Instalments that differ by a cent (a price that does not divide evenly) are still "N payments of about".
  const even = amounts.size === 1;
  return `${offer.instalments} payments of ${even ? "" : "about "}${formatMoney(first, currency)} ${intervalText(offer.interval_days)}`;
}

/** What it costs on top of the price: nothing, or the interest with the rate. */
export function costText(offer: Pick<BnplOffer, "customer_cost" | "apr" | "total_payable">, currency: string): string {
  if (offer.customer_cost <= 0) {
    return `No interest · total ${formatMoney(offer.total_payable, currency)}`;
  }
  return `${formatMoney(offer.customer_cost, currency)} interest (${offer.apr}% APR) · total ${formatMoney(offer.total_payable, currency)}`;
}

/** The fields that all of these offers need between them, in the order a form asks for them. */
export function fieldsFor(offers: Pick<BnplOffer, "required_fields">[]): BnplOffer["required_fields"] {
  const order: BnplOffer["required_fields"] = ["email", "birthdate", "national_id", "monthly_income"];
  const needed = new Set(offers.flatMap((o) => o.required_fields));
  return order.filter((field) => needed.has(field));
}
