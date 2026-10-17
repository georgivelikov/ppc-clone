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
