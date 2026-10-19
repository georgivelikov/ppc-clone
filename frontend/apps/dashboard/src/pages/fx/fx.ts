/** The currencies Cutpay accepts, in the order the dashboard lists them. */
export const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];

/** An exchange rate with enough digits to read, whatever its size (0.2012 or 4.28 or 395). */
export function formatRate(rate: number, locale = "en"): string {
  return rate.toLocaleString(locale, { maximumSignificantDigits: 6 });
}
