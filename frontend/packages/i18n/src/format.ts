/** Number of digits after the decimal point for a currency, as the platform counts them (ISO 4217: HUF has two). */
function fractionDigits(currency: string): number {
  return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
}

/**
 * An amount in minor units the way people in that language write it: "€1,234.56" in English, "1234,56 €" in Bulgarian,
 * "1234,56 €" in Polish, "1 234,56 €" in Romanian. The grouping and the symbol position are the language's, the
 * currency is the payment's.
 */
export function formatMoney(minor: number, currency: string, locale = "en"): string {
  return new Intl.NumberFormat(locale, { style: "currency", currency }).format(minor / 10 ** fractionDigits(currency));
}

/** Bulgaria adopted the euro on 1 January 2026 at a fixed 1.95583 leva to the euro. */
export const BGN_PER_EUR = 1.95583;

/**
 * During the changeover Bulgarian prices are shown in both currencies. Returns the lev amount for a euro one, or null for
 * everything else (other currencies, other languages).
 */
export function bgnEquivalent(minor: number, currency: string, locale: string): string | null {
  if (currency !== "EUR" || !locale.toLowerCase().startsWith("bg")) return null;
  const leva = Math.round((minor / 100) * BGN_PER_EUR * 100);
  return new Intl.NumberFormat("bg", { style: "currency", currency: "BGN" }).format(leva / 100);
}

export function formatDate(value: string | number | Date, locale = "en", options: Intl.DateTimeFormatOptions = { dateStyle: "medium" }): string {
  return new Intl.DateTimeFormat(locale, options).format(new Date(value));
}

export function formatDateTime(value: string | number | Date, locale = "en"): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

export function formatNumber(value: number, locale = "en", options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(locale, options).format(value);
}
