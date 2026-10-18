/** Number of digits after the decimal point for a currency, e.g. 2 for EUR and 0 for JPY. */
function fractionDigits(currency: string): number {
  return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
}

/** Formats an amount in minor units (cents) for display, e.g. 2500 EUR -> "€25.00". */
// English formatting for now; checkout localisation arrives in phase 7.
export function formatMoney(minor: number, currency: string, locale = "en"): string {
  const digits = fractionDigits(currency);
  return new Intl.NumberFormat(locale, { style: "currency", currency }).format(minor / 10 ** digits);
}

/**
 * Converts what a person typed ("12.34") to minor units (1234); null if it is not a valid positive amount. With
 * {@code allowNegative}, "-5.00" is accepted too (a discount), but zero never is.
 */
export function toMinorUnits(input: string, currency: string, allowNegative = false): number | null {
  const normalised = input.trim().replace(",", ".");
  if (!(allowNegative ? /^-?\d+(\.\d+)?$/ : /^\d+(\.\d+)?$/).test(normalised)) return null;
  const digits = fractionDigits(currency);
  const [, fraction = ""] = normalised.replace("-", "").split(".");
  if (fraction.length > digits) return null;
  const minor = Math.round(Number(normalised) * 10 ** digits);
  return minor !== 0 && (allowNegative || minor > 0) && Number.isSafeInteger(minor) ? minor : null;
}

/** Minor units as a plain decimal for an input field, e.g. 1250 EUR -> "12.50". */
export function toMajorString(minor: number, currency: string): string {
  const digits = fractionDigits(currency);
  return (minor / 10 ** digits).toFixed(digits);
}
