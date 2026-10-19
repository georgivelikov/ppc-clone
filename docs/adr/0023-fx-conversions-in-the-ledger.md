# 0023 — Foreign exchange: conversions in the ledger

- **Status:** Accepted
- **Date:** 2026-10-19

## Context
A customer can pay in any of eight currencies. A merchant who sells in several wants either to hold each currency or to settle in one. Converting money is a posting problem first (every entry must balance), a rounding problem second (minor units, different decimals) and a pricing problem third (the platform keeps a margin).

The plan allowed a small service of its own, or a module in an existing one. It is a module of the **ledger**.

## Decision

### Where it lives
Rates, quotes, conversions and the merchant's settings are tables and code in the ledger (`dev.cutpay.ledger.fx`). A conversion must be posted in the same transaction that checks the balance and records the conversion; with the ledger as owner that is one local transaction, instead of a call between services that can half-succeed. `fx.events` (`RatesUpdated`, `CurrencyConverted`) tell other services what happened.

### Rates
Every rate is stored per one euro (`fx_rates`: EUR/PLN = 4.28); the rate between two other currencies is the quotient of two of them, kept to twelve decimals so a rate such as HUF to EUR (0.0025...) keeps six significant digits. They are seeded in the migration and moved by a job once a minute with a small random step, never more than 10% from the seed, so they look alive but stay sensible. The rows are only added (a history), and old ones are trimmed to two days, keeping the newest of each. ECB reference rates were not fetched.

### Rounding, written once
`FxMath.convert` is the only place that does it. The market value of an amount is rounded half-even to a minor unit. What the merchant receives is the market value less the markup, **rounded down**, so the merchant is never paid more than the amount is worth. The markup is the difference, so the three always add up. The number of decimals of each currency comes from ISO 4217 as the JDK has it, and the two currencies' difference is scaled between them. All eight currencies of the platform have two decimals, including the forint (ISO says two, although nobody uses the fillér); a test with the yen, which has none, shows the scaling works.

### Two entries, one per currency
An entry never mixes currencies, and the trial balance is checked per currency. A conversion is therefore two entries, with the platform's own `platform_fx` account of each currency as the other side:

| Currency | Account | |
|---|---|---|
| source | merchant available | debit the amount |
| source | platform fx | credit the amount |
| target | platform fx | debit the market value |
| target | merchant available | credit what the merchant receives |
| target | platform fees | credit the markup (the platform's revenue) |

Each entry balances in its own currency. What is left on the FX accounts is the platform's open position: long what merchants sold it, short what it gave them. A real platform would hedge it; here it is visible and harmless. The merchant sees two balance transactions (type `conversion`), the markup as the fee of the arriving side, and payouts is told by `FundsAvailable` that less is payable in one currency and more in the other.

### Quotes
A quote works out the conversion at the current rate and promises it for 15 minutes (on the simulated clock) for exactly that amount and pair. Converting with it takes a row lock, so it is used once; a used, expired or foreign quote is refused with a clear code. Converting without a quote uses the rate of that moment. Only **available** money can be converted, and the balance row is locked while it is checked, so two conversions cannot spend the same money.

### Automatic conversion happens when funds become available
A merchant can keep several currencies and ask for the rest to be converted (`merchant_currency_settings`). The conversion is done when funds become available, in the job that already moves pending money to available, at the rate of that moment, into the first settlement currency. Converting earlier, at payment, would have made the pending release and the refunds work on money that has changed currency; converting at release leaves the payment's own life in the currency it was paid in and gives a refund of an old payment a simple answer (it is refunded in the payment's currency, from whatever balance the money is in). The markup is the platform's to set: the merchant sees it and cannot lower it.

## Consequences
- The merchant's settings are created with the merchant, in their default currency, with nothing converted until asked. A merchant created before this phase has no row and is left as it was.
- A refund of a payment whose money was converted is paid from the available balance in the payment's currency, which may be negative; converting back is the merchant's choice. Not reversed automatically.
- Payment events carry `presentment_amount` and `presentment_currency`. The settlement amount is known only later, when the money is converted, and is on `CurrencyConverted`; it was not added to the payment events.
- Rates are simulated. Nothing in the design stops them coming from a feed.
