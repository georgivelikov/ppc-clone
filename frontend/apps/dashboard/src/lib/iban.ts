/**
 * IBAN checks that mirror the payouts service (ISO 13616: shape plus the mod-97 checksum), so a typo is
 * caught while typing instead of after a round trip.
 */

const SHAPE = /^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/;

/** Removes spaces and upper-cases; returns null if the input is not a valid IBAN. */
export function normaliseIban(input: string): string | null {
  const iban = input.replace(/\s+/g, "").toUpperCase();
  return SHAPE.test(iban) && checksumOk(iban) ? iban : null;
}

/** Move the first four characters to the end, turn letters into numbers (A=10 ... Z=35); the result mod 97 must be 1. */
function checksumOk(iban: string): boolean {
  const rearranged = iban.slice(4) + iban.slice(0, 4);
  let remainder = 0;
  for (const char of rearranged) {
    const value = Number.parseInt(char, 36);
    // Letters expand to two digits; feeding them one digit at a time keeps the number small.
    remainder = value < 10 ? (remainder * 10 + value) % 97 : (remainder * 100 + value) % 97;
  }
  return remainder === 1;
}

/** "BG80BNBG96611020345678" -> "BG80 BNBG 9661 1020 3456 78" */
export function formatIban(iban: string): string {
  return iban.replace(/(.{4})/g, "$1 ").trim();
}
