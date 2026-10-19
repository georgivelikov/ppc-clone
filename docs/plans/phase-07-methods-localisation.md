# Phase 7 — Payment methods, multi-currency & localisation

## Goal
Match Paypercut's CEE coverage: **Apple Pay / Google Pay**, **BLIK** (Poland), **8 currencies** (EUR, RON, PLN, HUF, CZK, GBP, USD, CHF) with **FX settlement**, and a checkout localised into **bg, ro, pl, hu, el, en** with local formatting.

## Services / apps touched
- `acquirer-sim` (wallet & BLIK simulators)
- `payments` (method types `apple_pay`, `google_pay`, `blik`)
- `backend/services/fx` (new, small) or module inside `payouts`
- `ledger`, `payouts` (multi-currency balances, conversion)
- `apps/checkout`, `apps/dashboard`, `packages/i18n`

## Wallets (simulated)
Real Apple/Google Pay need certificates/merchant IDs — we **simulate the UX & token flow**:
- Checkout shows "Pay" wallet button → opens a **fake wallet sheet** (modal styled generically, not Apple's trademarked UI): pick a stored test card, "authenticate" (fake Face ID/PIN) → produces a **network-token-like payload** (`DPAN`, cryptogram, eci).
- payments sends to acquirer-sim with `wallet_type`; wallet payments are SCA-compliant → no 3DS.
- Optional stretch: integrate the real **Google Pay TEST environment** (works without real cards, returns test tokens) — good learning, still no real money.
- Express checkout: wallet button on the demo-shop product page (shipping address & options from wallet sheet).

## BLIK (simulated)
- Customer enters 6-digit code on checkout. acquirer-sim: `777123` → approve after "bank app confirmation" (5 s async, push via SSE), `000000` → decline, others → invalid code.
- Fake **"Bank app"** page (`/sim/blik-app`) shows pending request with Approve/Reject — demonstrates async push-confirmation methods.
- Pricing: 1.29% + PLN 0.25 (as Paypercut).
- Only available for PLN.

## Multi-currency
- Presentment currency = what customer pays; settlement currency = merchant's payout account currency.
- Merchant can hold balances in multiple currencies (`accounts` per currency already supports this) or auto-convert.
```
fx_rates(base, quote, rate NUMERIC(18,8), source, as_of)      -- seeded + random walk job every minute
fx_conversions(id, from_amount, from_ccy, to_amount, to_ccy, rate, markup_bps, ledger_entry_id, created_at)
merchant_currency_settings(merchant_id, settlement_currencies TEXT[], auto_convert BOOL, fx_markup_bps default 100)
```
- Use `BigDecimal` for rates, convert back to minor units with currency's exponent (HUF has 2 officially but practically 0 — handle `Currency.getDefaultFractionDigits()` vs. real-world convention; ISO says HUF=2. Document it).
- FX conversion posts through a `platform_fx` account per currency; markup goes to platform revenue.
- Optional: fetch real ECB reference rates (read-only public XML) as seed.

## Localisation
- `packages/i18n`: react-i18next, namespaces per app, ICU messages; languages `en, bg, ro, pl, hu, el`.
- Locale detection: link/session `locale` param → `Accept-Language` → merchant default.
- Formatting via `Intl.NumberFormat` / `Intl.DateTimeFormat` (e.g. `1 234,56 лв.` vs `€1,234.56`; note Bulgaria uses EUR from 2026 — show both during transition as a nice touch).
- Local address formats & phone validation (libphonenumber-js).
- Backend: localised emails & PDFs (Spring `MessageSource`) — used in phase 8.
- Translation workflow: JSON files + a script that flags missing keys in CI.

## Kafka
- `fx.events`: `RatesUpdated`, `CurrencyConverted`
- payments events now include `presentment_amount/currency` and `settlement_amount/currency`.

## REST endpoints
- `GET /v1/fx/rates`, `POST /v1/fx/quotes` (locked rate for 15 min), `GET/PUT /v1/currency_settings`
- `GET /v1/balance` returns per-currency balances; `POST /v1/balance/convert`
- Checkout: `GET /checkout/{secret}/methods` → available methods per currency/country/device

## UI screens
- Checkout: method selector (Card, Wallet, BLIK, Instalments), wallet sheet, BLIK code input + waiting state, language switcher, currency display
- Dashboard: multi-currency balance cards, convert balance modal with quote countdown, payment methods settings (enable/disable per method), FX report
- Dashboard itself translated (at least en + bg)

## Learning objectives
- Payment method abstraction (strategy per method)
- Async push confirmations (BLIK)
- FX, rounding, currency exponents, rate locking
- i18n/l10n engineering

## Acceptance criteria
- Pay PLN order via BLIK 777123 → approve in fake bank app → succeeded; fee = 1.29% + 0.25 PLN.
- Customer pays RON 500, merchant settles EUR → ledger shows conversion with markup; trial balance = 0 per currency.
- Checkout renders correctly in all 6 languages (Playwright screenshot test per locale).
- Wallet payment skips 3DS.
- Tests: FX rounding unit tests per currency, method-availability rules tests, Playwright `blik.spec.ts`, `wallet.spec.ts`, `i18n.spec.ts`.

## As built
Status: built. Design decisions are in [ADR 0022](../adr/0022-wallets-and-blik.md) (wallets, BLIK, the list of methods), [ADR 0023](../adr/0023-fx-conversions-in-the-ledger.md) (rates, quotes, conversions) and [ADR 0024](../adr/0024-localisation.md) (languages and formatting). Where the build differs from the plan above:

- **Wallets.** The fake sheet is a dialog in the checkout with the wallet's stored test cards (`GET /vault/wallet/cards`); confirming "on the device" (`POST /vault/wallet/authenticate`) returns a device token with a DPAN, a cryptogram and an ECI. The token is a vault token marked with its wallet, so payments, 3-D Secure rules and the card fee follow from it: a wallet payment skips 3-D Secure, even for the card that asks for it when typed. The real Google Pay TEST environment and the express checkout on the demo shop were not built.
- **BLIK.** `777123` waits for the pretend bank app (`/sim/blik-app`, also a page a person can use) and is approved by itself after five seconds; `000000` is declined; other codes are not valid and leave the payment open. The answer arrives by a call back, so the payment is `requires_action` with a next action of type `blik`. It is for PLN, taken at once, and costs 1.29% + PLN 0.25 (a constant of the ledger). SSE was not used for the waiting page: the checkout asks the session every second. A request nobody answers is not expired.
- **Methods.** `GET /checkout/sessions/{secret}/methods` (the plan's `/checkout/{secret}/methods`) is decided by `PaymentMethodRules`; the dashboard has Settings, Payment methods to switch wallets and BLIK off (`/v1/payment_method_settings`). Device is not a rule yet.
- **FX is part of the ledger, not a service.** Tables `fx_rates` (per euro, history kept two days), `fx_quotes`, `fx_conversions` and `merchant_currency_settings` (V5 migration) and a `platform_fx` account per currency. `fx.events` has `RatesUpdated` and `CurrencyConverted`. The rate job runs on the real clock once a minute; quotes use the ledger's simulated clock and also say `expires_in`. Rates are not fetched from the ECB.
- **Conversion.** Available money only, two entries (one per currency), market value half-even and the merchant's part rounded down; the markup (1%, set by the platform, visible to the merchant) goes to platform fees. Automatic conversion into the first settlement currency happens when funds become available, not when the payment succeeds. Trial balance is checked per currency. The forint keeps two decimals as ISO 4217 has it.
- **Endpoints.** `GET /v1/fx/rates`, `POST /v1/fx/quotes`, `POST /v1/balance/convert`, `GET /v1/fx/conversions`, `GET /v1/fx/report`, `GET/PUT /v1/currency_settings`. `GET /v1/balance` was already per currency. Payment events carry `presentment_amount` and `presentment_currency`; settlement amounts are on `CurrencyConverted`.
- **Dashboard.** Balances shows each currency with a Convert button, the conversion dialog (quote, what arrives, markup, countdown, refusal of an expired quote), the conversion report and the currency settings; a conversion is a transaction type. The FX report is the totals per currency and the latest conversions.
- **Languages.** `@cutpay/i18n` with react-i18next (i18next suffix plurals, not ICU). The checkout is in `en, bg, ro, pl, hu, el`; the dashboard shell, home, payments, balances, currencies and payment methods in `en, bg`. `?locale=`, the language the customer chose, the `locale` of the payment, the browser, then English. Bulgarian shows leva next to euros. `pnpm i18n:check` finds missing keys, lost placeholders and missing plural forms.
- **Not built.** libphonenumber validation and local address formats (the payment link page is English), localised emails and PDFs (phase 8), translating the payment link, invoice and card-saving pages and the rest of the dashboard, the express checkout, the real Google Pay TEST integration, ECB rates, the merchant's default language.
- **Tests.** Unit: FX rounding for every pair of currencies and for currencies with another number of decimals, method rules, the translation check, locale choice and formatting. Integration (Postgres, Kafka): conversions, quotes, auto-convert, rate walk, BLIK and wallet payments, method settings, locale on a payment; in the simulator the wallet and BLIK flows; in the gateway the routes and key scopes. Component tests: checkout methods and all six languages, dashboard Bulgarian, conversion dialog. Playwright: `blik.spec.ts`, `wallet.spec.ts`, `fx.spec.ts` (RON 500 settled in euros, a conversion by hand), `i18n.spec.ts` (every language, desktop and phone, with screenshots).
