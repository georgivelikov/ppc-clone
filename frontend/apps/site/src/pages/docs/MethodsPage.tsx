import { Link } from "react-router";
import { CodeBlock } from "../../components";

/** What the docs promise about BLIK, currencies and languages. The contract test checks these against the platform's own source. */
export const BLIK = { approves: "777123", declines: "000000", bps: 129, fixed: 25, currency: "PLN" } as const;
export const FX = { markupBps: 100, quoteMinutes: 15, bgnPerEur: 1.95583 } as const;
export const LOCALES = ["en", "bg", "ro", "pl", "hu", "el"] as const;

const SESSION = `curl -X POST http://localhost:8080/v1/checkout_sessions \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "amount": 12000, "currency": "PLN", "locale": "pl" }'

# The checkout opens in Polish. A customer can still switch, and ?locale=bg on the link wins over what you set.`;

const SETTINGS = `# Turn BLIK off, keep wallets on
curl -X PUT http://localhost:8080/v1/payment_method_settings \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "methods": [ { "method": "blik", "enabled": false } ] }'`;

const QUOTE = `# What would RON 493.45 be worth in euros? The rate is held for 15 minutes.
curl -X POST http://localhost:8080/v1/fx/quotes \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "from_currency": "RON", "to_currency": "EUR", "amount": 49345 }'

# {
#   "id": "fxq_01J9...", "rate": 0.201207243461, "markup_bps": 100,
#   "mid_amount": 9928,  "to_amount": 9829,  "markup_amount": 99,
#   "expires_at": "2026-10-19T10:15:00Z"
# }

# Convert at exactly that quote (the amount must match it)
curl -X POST http://localhost:8080/v1/balance/convert \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "from_currency": "RON", "to_currency": "EUR", "amount": 49345, "quote_id": "fxq_01J9..." }'`;

const AUTO = `# Keep euros, and convert everything else by itself when it becomes available
curl -X PUT http://localhost:8080/v1/currency_settings \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "settlement_currencies": ["EUR"], "auto_convert": true }'`;

export function MethodsPage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Wallets, BLIK, currencies and languages</h1>
      <p className="mt-3 text-muted">
        Beyond cards and instalments the checkout can take wallet and BLIK payments, any of eight currencies and speak six languages.
        None of it moves real money: the wallets, the bank app and the exchange rates are simulated.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Ways of paying</h2>
      <p className="mt-2 text-sm">
        The checkout asks which methods fit this payment (<code>GET /checkout/sessions/&#123;secret&#125;/methods</code>) and shows a tab for each. You switch
        wallets and BLIK on or off under Settings, Payment methods in the dashboard or with the API. Cards cannot be switched off.
      </p>
      <CodeBlock language="Shell" label="Switch a method off" code={SETTINGS} />

      <h3 className="mt-6 font-semibold">Apple Pay and Google Pay</h3>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          A pretend wallet opens in the page. The customer picks one of its stored test cards and &quot;confirms with Face ID or PIN&quot;. The wallet hands
          back a device token with a cryptogram, like a real one, and the payment uses that.
        </li>
        <li>
          The wallet has already authenticated the customer on their device, so <strong>there is no 3-D Secure step</strong>, even for the card that always
          asks for it when typed in.
        </li>
        <li>
          The payment is priced like the card behind the wallet, and says which wallet it was on the payment page and in the API
          (<code>payment_method_type</code> is <code>apple_pay</code> or <code>google_pay</code>).
        </li>
        <li>A wallet token belongs to the wallet: it is not saved as a card to charge later.</li>
      </ul>

      <h3 className="mt-6 font-semibold">BLIK</h3>
      <p className="mt-2 text-sm">
        BLIK is a Polish method for paying in złoty: the customer types a six digit code from their banking app, then confirms the payment in the app. So the
        answer comes later, from the bank, and the checkout waits for it.
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          Only for payments in <code>{BLIK.currency}</code> that are taken at once, not captured later.
        </li>
        <li>
          <code>{BLIK.approves}</code> is accepted and waits for the customer in the pretend bank app (<code>/vault/sim/blik-app</code>); if nobody answers, it is
          approved after a few seconds. <code>{BLIK.declines}</code> is declined at once. Any other six digits are not a valid code and the customer can try
          another.
        </li>
        <li>
          Cost: {BLIK.bps / 100}% + PLN {BLIK.fixed / 100}, whatever the merchant&apos;s card plan is.
        </li>
      </ul>

      <h2 className="mt-10 text-xl font-semibold">Currencies</h2>
      <p className="mt-2 text-sm">
        A customer pays in the currency of the payment (the <em>presentment</em> currency). Money stays in that currency on your balance until you decide
        otherwise; you can hold balances in every currency we support (EUR, RON, PLN, HUF, CZK, GBP, USD and CHF), or convert.
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          <strong>Rates</strong> are simulated: each starts at a fixed value and takes a small random step every minute, never more than 10% from where it
          started. <code>GET /v1/fx/rates</code> shows them for any base currency.
        </li>
        <li>
          <strong>Markup.</strong> A conversion costs {FX.markupBps / 100}% over the market rate. What you receive is rounded down and the market value to the
          nearest minor unit, so you are never paid more than it is worth; the difference is the markup.
        </li>
        <li>
          <strong>Quotes.</strong> A quote holds a rate for {FX.quoteMinutes} minutes, for one conversion of exactly its amount. A used or expired quote is
          refused.
        </li>
        <li>
          <strong>Only available money</strong> can be converted, not what is still pending. With <code>auto_convert</code> on, money in a currency you do not
          keep is converted into your first settlement currency as it becomes available, at the rate of that moment.
        </li>
        <li>
          Every conversion is two journal entries, one in each currency, with the platform&apos;s own FX account as the other side, so the books balance in
          every currency on their own (ADR 0023).
        </li>
        <li>
          The forint has two decimals here, as in ISO 4217, so amounts are in fillér even though nobody pays with them.
        </li>
      </ul>
      <CodeBlock language="Shell" label="Quote and convert" code={QUOTE} />
      <CodeBlock language="Shell" label="Convert by itself" code={AUTO} />

      <h2 className="mt-10 text-xl font-semibold">Languages</h2>
      <p className="mt-2 text-sm">
        The hosted checkout is translated into English, Bulgarian, Romanian, Polish, Hungarian and Greek, and writes prices and dates the way each language
        does. In Bulgarian a euro price also shows its value in leva at the fixed rate of {FX.bgnPerEur} while people get used to the euro. The dashboard is
        available in English and Bulgarian.
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          The language is, in order: <code>?locale=</code> in the link or the one the customer picked before, the <code>locale</code> you gave when creating the
          payment, the browser&apos;s languages, then English.
        </li>
        <li>The customer can switch from the page; the choice is remembered in their browser.</li>
      </ul>
      <CodeBlock language="Shell" label="Open the checkout in Polish" code={SESSION} />
      <p className="mt-6 text-sm text-muted">
        The endpoints are in the <Link to="/docs/api" className="text-brand-700 hover:underline">API reference</Link> under Payment methods and Currencies.
      </p>
    </>
  );
}
