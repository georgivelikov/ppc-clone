import { currentLocale, setLocale } from "./setup";
import { LOCALES, LOCALE_NAMES, type Locale } from "./locale";
import { useTranslation } from "react-i18next";

/** A plain select of the languages, each in its own name, so a customer who cannot read the page can still find theirs. */
export function LanguageSwitcher({ label, className, locales = LOCALES }: { label: string; className?: string; locales?: readonly Locale[] }) {
  const { i18n } = useTranslation();
  const value = LOCALES.find((locale) => locale === i18n.language) ?? currentLocale();
  return (
    <select
      aria-label={label}
      value={value}
      className={className ?? "rounded-md border border-border bg-surface px-2 py-1 text-xs"}
      onChange={(event) => void setLocale(event.target.value as Locale)}
    >
      {locales.map((locale) => (
        <option key={locale} value={locale} lang={locale}>
          {LOCALE_NAMES[locale]}
        </option>
      ))}
    </select>
  );
}
