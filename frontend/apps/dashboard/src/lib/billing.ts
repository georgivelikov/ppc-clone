import type { MonthChurn, Price, PriceInterval } from "@cutpay/api-client";
import { formatMoney } from "@cutpay/ui";

const UNIT: Record<PriceInterval, [string, string]> = {
  day: ["day", "days"],
  week: ["week", "weeks"],
  month: ["month", "months"],
  year: ["year", "years"],
};

/** "every month", "every 3 months": how often a recurring price is charged. */
export function intervalLabel(interval: PriceInterval, count: number): string {
  const [one, many] = UNIT[interval];
  return count === 1 ? `every ${one}` : `every ${count} ${many}`;
}

/** "€15.00 every month", or just the amount for a one-time price. */
export function priceLabel(price: Pick<Price, "unit_amount" | "currency" | "interval" | "interval_count">): string {
  const amount = formatMoney(price.unit_amount, price.currency);
  return price.interval ? `${amount} ${intervalLabel(price.interval, price.interval_count)}` : amount;
}

/** The share of the subscriptions that existed when a month began that ended during it, as a percentage. */
export function churnPercent(month: Pick<MonthChurn, "at_start" | "ended">): number {
  return month.at_start > 0 ? (month.ended / month.at_start) * 100 : 0;
}
