/** The languages Cutpay is translated into. English is the source: every other file is checked against it. */
export const LOCALES = ["en", "bg", "ro", "pl", "hu", "el"] as const;
export type Locale = (typeof LOCALES)[number];

export const DEFAULT_LOCALE: Locale = "en";

/** Each language in its own words, for the switcher. */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: "English",
  bg: "Български",
  ro: "Română",
  pl: "Polski",
  hu: "Magyar",
  el: "Ελληνικά",
};

/** "bg-BG", "pl_PL" and "EL" all mean a language we have; "de" does not. */
export function normaliseLocale(tag: string | null | undefined): Locale | null {
  if (!tag) return null;
  const language = tag.trim().toLowerCase().split(/[-_]/)[0];
  return (LOCALES as readonly string[]).includes(language ?? "") ? (language as Locale) : null;
}

export interface LocaleSources {
  /** Chosen explicitly: a `?locale=` in the link, or the customer using the language switcher. */
  chosen?: string | null;
  /** Set by the merchant when the checkout was created. */
  session?: string | null;
  /** The browser's languages, most wanted first (`navigator.languages`, or an Accept-Language header). */
  browser?: readonly string[];
  /** The merchant's own language, last resort before English. */
  merchantDefault?: string | null;
}

/**
 * Picks the language to show: what was asked for explicitly, then what the merchant set for this payment, then what the
 * browser prefers, then the merchant's default, and English when none of those is a language we have.
 */
export function detectLocale(sources: LocaleSources = {}): Locale {
  const candidates = [sources.chosen, sources.session, ...(sources.browser ?? []), sources.merchantDefault];
  for (const candidate of candidates) {
    const found = normaliseLocale(candidate);
    if (found) return found;
  }
  return DEFAULT_LOCALE;
}

/** Reads an Accept-Language header such as "pl,en-US;q=0.8,en;q=0.7" into languages, best first. */
export function parseAcceptLanguage(header: string | null | undefined): string[] {
  if (!header) return [];
  return header
    .split(",")
    .map((part, index) => {
      const [tag = "", ...params] = part.trim().split(";");
      const q = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
      const weight = q ? Number(q.slice(2)) : 1;
      return { tag: tag.trim(), weight: Number.isNaN(weight) ? 0 : weight, index };
    })
    .filter((entry) => entry.tag && entry.tag !== "*" && entry.weight > 0)
    .sort((a, b) => b.weight - a.weight || a.index - b.index)
    .map((entry) => entry.tag);
}
