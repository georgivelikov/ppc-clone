# 0024 — Localisation of the checkout and the dashboard

- **Status:** Accepted
- **Date:** 2026-10-19

## Context
The hosted checkout is shown to customers in Bulgaria, Romania, Poland, Hungary and Greece, and the dashboard to merchants there. Language is only part of it: amounts, dates and the words for a number of things ("2 payments", "5 payments") change too.

## Decision

### One package, messages per app, English as the source
`@cutpay/i18n` holds the setup (react-i18next), the choice of language, the formatting helpers and the messages, in `src/locales/<language>/<namespace>.json`. Each app has one namespace: `checkout` in all six languages (`en`, `bg`, `ro`, `pl`, `hu`, `el`), `dashboard` in English and Bulgarian. A language without a message falls back to English, and a message missing in English shows its key, which is easy to spot. Messages use i18next's `{{placeholder}}` and `<tag>` syntax rather than ICU; plurals use i18next's suffixes (`_one`, `_few`, `_many`, `_other`) resolved by the browser's `Intl.PluralRules`, so Polish and Romanian get the forms they need without a plugin.

### Choosing the language
In order: a language in the link (`?locale=`) or the one the customer picked before (kept in the browser), the language the merchant set when creating the payment (`locale` on the payment, returned with the checkout session), the browser's languages, English. The customer can switch from the page. The dashboard offers English and Bulgarian only; any other language shows English.

### Formatting is the language's, the currency is the payment's
Amounts, dates and numbers go through `Intl` with the language being shown (`1,234.56 €` in English, `1234,56 €` in Bulgarian), never string building. The currency stays that of the payment. During Bulgaria's changeover to the euro, a euro price shown in Bulgarian also shows its value in leva at the fixed rate of 1.95583. The forint has two decimals as in ISO 4217.

### Translations are checked
`pnpm i18n:check` (also run by the package's tests) compares every language with English and reports a missing key, a key that is not in English, an empty message, a lost placeholder or tag, and a plural form the language needs and the message lacks (Polish needs four forms, Romanian three). Tests of the checkout render every language, make sure no message key shows through, and cover switching, the merchant's choice, the Bulgarian lev amount and plurals; a Playwright test opens the checkout in every language on a desktop and a phone window, checks the page does not scroll sideways and keeps a screenshot of each.

## Consequences
- The checkout page and everything on it is translated. The payment link, invoice and card-saving pages, and the embedded card element, are still English (they share the frame, which shows its language switcher only on pages that are translated). The dashboard's shell, home, payments list, balances, currencies and payment methods are translated; the rest of the dashboard is English.
- Messages from the backend (a decline reason, a provider's sentence) come in English and are shown as they are.
- Phone and address validation (libphonenumber) and localised emails and PDFs were not built; they belong to the pages and documents that collect and send them.
- A translation written by someone who speaks the language would improve the ones here; the check keeps their structure honest, not their wording.
