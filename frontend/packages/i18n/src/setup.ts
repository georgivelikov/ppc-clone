import i18next, { type i18n as I18nInstance, type Resource } from "i18next";
import { initReactI18next, I18nextProvider, Trans, useTranslation } from "react-i18next";
import { DEFAULT_LOCALE, detectLocale, LOCALES, normaliseLocale, type Locale } from "./locale";

import { bgnEquivalent, formatDate, formatDateTime, formatMoney, formatNumber } from "./format";
export { I18nextProvider, Trans, useTranslation };
export { i18next };

/** The key the customer's own choice of language is remembered under. */
export const STORAGE_KEY = "cutpay.locale";

/** What was chosen last time, if the browser lets us ask. */
export function storedLocale(): Locale | null {
  try {
    return normaliseLocale(globalThis.localStorage?.getItem(STORAGE_KEY));
  } catch {
    return null;
  }
}

/** The language the browser asks for (`navigator.languages`), best first. */
export function browserLanguages(): readonly string[] {
  return typeof navigator === "undefined" ? [] : navigator.languages?.length ? navigator.languages : [navigator.language];
}

/** Resources are `{ locale: { namespace: messages } }`, so each app brings the namespaces it uses. */
export type Resources = Record<string, Record<string, unknown>>;

export interface SetupOptions {
  resources: Resources;
  locale?: Locale;
  defaultNamespace: string;
}

/**
 * Starts translating. Safe to call again (tests, hot reload): the messages are replaced and the language switched.
 * Missing keys fall back to English, and a missing English key shows the key itself, which is easy to spot.
 */
export function setupI18n({ resources, locale = DEFAULT_LOCALE, defaultNamespace }: SetupOptions): I18nInstance {
  const bundle = resources as unknown as Resource;
  if (!i18next.isInitialized) {
    void i18next.use(initReactI18next).init({
      resources: bundle,
      lng: locale,
      fallbackLng: DEFAULT_LOCALE,
      supportedLngs: [...LOCALES],
      defaultNS: defaultNamespace,
      ns: Object.keys(resources[DEFAULT_LOCALE] ?? { [defaultNamespace]: {} }),
      interpolation: { escapeValue: false }, // React escapes already
      returnNull: false,
      initAsync: false,
    });
  } else {
    for (const [lng, namespaces] of Object.entries(resources)) {
      for (const [ns, messages] of Object.entries(namespaces)) {
        i18next.addResourceBundle(lng, ns, messages as object, true, true);
      }
    }
    i18next.setDefaultNamespace(defaultNamespace);
    void i18next.changeLanguage(locale);
  }
  applyDocumentLanguage(locale);
  return i18next;
}

function applyDocumentLanguage(locale: string): void {
  if (typeof document !== "undefined") document.documentElement.lang = locale;
}

/** Switches the language, and remembers it when the customer chose it themselves. */
export async function setLocale(locale: Locale, remember = true): Promise<void> {
  await i18next.changeLanguage(locale);
  applyDocumentLanguage(locale);
  if (remember) {
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, locale);
    } catch {
      // not available (private window, blocked storage): the choice just does not last
    }
  }
}

/** The language being shown now. */
export function currentLocale(): Locale {
  return normaliseLocale(i18next.language) ?? DEFAULT_LOCALE;
}

export { detectLocale };

/** Formatting in the language being shown; re-renders when the language changes. */
export function useLocaleFormat() {
  const { i18n } = useTranslation();
  const locale = normaliseLocale(i18n.language) ?? DEFAULT_LOCALE;
  return {
    locale,
    money: (minor: number, currency: string) => formatMoney(minor, currency, locale),
    /** The lev amount next to a euro one while Bulgaria changes over, or null. */
    bgn: (minor: number, currency: string) => bgnEquivalent(minor, currency, locale),
    date: (value: string | number | Date) => formatDate(value, locale),
    dateTime: (value: string | number | Date) => formatDateTime(value, locale),
    number: (value: number, options?: Intl.NumberFormatOptions) => formatNumber(value, locale, options),
  };
}

