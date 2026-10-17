export type CardBrand = "visa" | "mastercard" | "amex" | "unknown";

export const digitsOnly = (value: string): string => value.replace(/\D/g, "");

export function detectBrand(number: string): CardBrand {
  const digits = digitsOnly(number);
  if (digits.startsWith("4")) return "visa";
  if (/^(5[1-5]|2[2-7])/.test(digits)) return "mastercard";
  if (/^3[47]/.test(digits)) return "amex";
  return "unknown";
}

/** The Luhn checksum catches most typos in a card number. */
export function luhnValid(number: string): boolean {
  const digits = digitsOnly(number);
  if (digits.length < 12 || digits.length > 19) return false;
  let sum = 0;
  let double = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let digit = Number(digits[i]);
    if (double) {
      digit *= 2;
      if (digit > 9) digit -= 9;
    }
    sum += digit;
    double = !double;
  }
  return sum % 10 === 0;
}

/** Groups digits the way the card is printed: 4-4-4-4, or 4-6-5 for American Express. */
export function formatCardNumber(input: string): string {
  const digits = digitsOnly(input).slice(0, 19);
  if (detectBrand(digits) === "amex") {
    return [digits.slice(0, 4), digits.slice(4, 10), digits.slice(10, 15)].filter(Boolean).join(" ");
  }
  return (digits.match(/.{1,4}/g) ?? []).join(" ");
}

/** Turns "1230" or "12/30" into "12 / 30" while typing. */
export function formatExpiry(input: string): string {
  const digits = digitsOnly(input).slice(0, 4);
  if (digits.length < 3) return digits;
  return `${digits.slice(0, 2)} / ${digits.slice(2)}`;
}

export function parseExpiry(input: string): { month: number; year: number } | null {
  const digits = digitsOnly(input);
  if (digits.length !== 4) return null;
  const month = Number(digits.slice(0, 2));
  const year = 2000 + Number(digits.slice(2));
  return month >= 1 && month <= 12 ? { month, year } : null;
}

export function isExpired(month: number, year: number, now: Date = new Date()): boolean {
  return year < now.getFullYear() || (year === now.getFullYear() && month < now.getMonth() + 1);
}
