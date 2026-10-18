import { Link } from "react-router";
import { CodeBlock } from "../../components";

/** The pretend providers, as the docs describe them. The contract test checks these against the platform's own source. */
export const PROVIDERS = [
  { code: "splitnow", name: "SplitNow", plans: "3 or 4 payments, two weeks apart, no interest", min: 5000, max: 150000, feeBps: 350, answer: "at once" },
  { code: "lendo", name: "Lendo", plans: "6, 12 or 24 monthly payments at 9.9% a year", min: 10000, max: 1000000, feeBps: 290, answer: "later, by a signed callback" },
  { code: "kreditko", name: "KreditKo", plans: "6, 12 or 24 monthly payments at 12.9% a year", min: 20000, max: 1500000, feeBps: 450, answer: "by asking; big loans wait for review" },
  { code: "paylatereu", name: "PayLaterEU", plans: "the whole price after 30 days, no interest", min: 2000, max: 80000, feeBps: 250, answer: "at once, but it is the flaky one" },
] as const;

const euros = (minor: number) => `€${(minor / 100).toLocaleString("en-US", { minimumFractionDigits: 2 })}`;

const SETTINGS = `# Recommend the cheapest provider, and put KreditKo first when the customer chooses
curl -X PUT http://localhost:8080/v1/bnpl/settings \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{
    "routing_mode": "cheapest",
    "providers": [
      { "code": "kreditko", "priority": 0 },
      { "code": "paylatereu", "enabled": false }
    ]
  }'`;

const DECISION = `curl http://localhost:8080/v1/bnpl/routing_decisions/pay_01J9... \\
  -H "Authorization: Bearer sk_test_..."

# {
#   "chosen_provider": "paylatereu",
#   "reason": "Chose PayLaterEU: cheapest eligible (2.5% vs 2.9% for Lendo); KreditKo excluded: not available in NL",
#   "ranked": [ { "provider": "paylatereu", "rank": 1, "best": { "plan": "paylatereu_30", "merchant_fee": 750 }, ... } ],
#   "excluded": [ { "provider": "kreditko", "code": "country", "message": "not available in NL" } ]
# }`;

const ANALYTICS = `curl "http://localhost:8080/v1/bnpl/analytics?days=30" \\
  -H "Authorization: Bearer sk_test_..."`;

export function InstalmentsPage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Pay in instalments</h1>
      <p className="mt-3 text-muted">
        One integration, several buy-now-pay-later providers. The customer chooses how to pay in parts; you are paid up front.
      </p>

      <h2 className="mt-10 text-xl font-semibold">How it works</h2>
      <ol className="mt-2 list-decimal space-y-1 pl-5 text-sm">
        <li>
          At the checkout a <strong>Pay in instalments</strong> tab appears next to the card form, with what each provider would
          offer for this price: how many payments, how often, and what it costs the customer.
        </li>
        <li>The customer picks a plan and tells the provider what it needs (always an email; some also a date of birth, an ID number or an income).</li>
        <li>
          The provider decides. When it approves, the payment <code>succeeds</code> and you receive the price <strong>less the
          provider&apos;s fee</strong> through the normal balance and payout schedule. The customer then pays the provider.
        </li>
      </ol>
      <p className="mt-2 text-sm">
        A decline, a provider that cannot be reached or a customer who gives up leaves the payment open, so they can try another
        provider or pay by card. Instalments are not offered for payments that are held for capture or split between accounts.
      </p>

      <h2 className="mt-10 text-xl font-semibold">The pretend providers</h2>
      <p className="mt-2 text-sm">
        There is no real lender behind this. A simulator plays four, each with its own limits, fee and way of answering. The fee is
        what you pay, as a share of the price.
      </p>
      <div className="mt-3 overflow-x-auto rounded-lg border border-border">
        <table className="w-full text-left text-sm" aria-label="Providers">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">Provider</th>
              <th className="px-3 py-2 font-medium">Plans</th>
              <th className="px-3 py-2 font-medium">Price</th>
              <th className="px-3 py-2 font-medium">Fee</th>
              <th className="px-3 py-2 font-medium">Answers</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {PROVIDERS.map((p) => (
              <tr key={p.code}>
                <td className="px-3 py-2 font-medium">{p.name}</td>
                <td className="px-3 py-2">{p.plans}</td>
                <td className="px-3 py-2 whitespace-nowrap">
                  {euros(p.min)} to {euros(p.max)}
                </td>
                <td className="px-3 py-2">{p.feeBps / 100}%</td>
                <td className="px-3 py-2">{p.answer}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-3 text-sm">To try each of them:</p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          <strong>SplitNow</strong> turns down the email <code>fraud@example.com</code> and approves any other.
        </li>
        <li>
          <strong>Lendo</strong> scores the date of birth. Most dates are approved; some are not, and it also turns down anyone under 18. It answers a few seconds later.
        </li>
        <li>
          <strong>KreditKo</strong> wants a national ID (6 to 16 letters or digits) and a monthly income, and approves when the income is at least three monthly payments. Loans above €5,000 go to a review that ends by itself after half a minute.
        </li>
        <li>
          <strong>PayLaterEU</strong> turns down <code>debt@example.com</code>. About one call in five hangs and times out, on purpose.
        </li>
      </ul>
      <p className="mt-2 text-sm">
        The simulator has a page at <code>http://localhost:8089</code> to switch a provider off, slow it down or make it hang.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Choosing the provider</h2>
      <p className="mt-2 text-sm">
        Cutpay first leaves out the providers that cannot help: you turned them off, they do not work in that currency or country,
        the price is outside their limits, or their circuit is open (see below). The rest are asked at the same time and a provider
        that has not answered after two seconds is simply missing from the list. What happens next is your choice,{" "}
        <code>routing_mode</code>:
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li><code>customer_choice</code>: the customer picks, and the offers are shown in your order.</li>
        <li><code>cheapest</code>: recommend the provider that keeps the least.</li>
        <li><code>fastest</code>: recommend the one that usually decides soonest.</li>
        <li><code>best_approval</code>: recommend the one that approves most customers.</li>
        <li><code>weighted</code>: a mix of the three, by the <code>weights</code> you set.</li>
      </ul>
      <p className="mt-2 text-sm">
        Speed and approval rate are measured from the last week of decisions, leaning on what each provider is expected to do until
        there is a record. With a recommending mode the checkout offers a <strong>Best option</strong>, and if the customer agrees, the
        next best provider is tried after a decline or a failure, at most two more.
      </p>
      <CodeBlock language="Shell" label="Settings" code={SETTINGS} />
      <p className="mt-2 text-sm">
        A <code>priority</code> is a position, 0 first: the provider takes exactly that place and the others keep their order around it.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Why was that provider chosen?</h2>
      <p className="mt-2 text-sm">
        Every time offers are worked out, the reasoning is kept. The payment page in the dashboard shows it, and so does the API.
      </p>
      <CodeBlock language="Shell" label="Explain a choice" code={DECISION} />

      <h2 className="mt-10 text-xl font-semibold">When a provider is down</h2>
      <p className="mt-2 text-sm">
        Each provider has a circuit breaker. After several calls that time out or fail, its circuit <strong>opens</strong>: for ten
        seconds it is not called and not offered, so no customer waits for it. Then one request is let through as a probe; if the provider
        answers, it is back. A provider that answers and says no (a price outside its limits) is working, and never opens its circuit.
      </p>
      <CodeBlock language="Shell" label="How each provider has done" code={ANALYTICS} />

      <h2 className="mt-10 text-xl font-semibold">Money</h2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          You are credited the price less the provider&apos;s fee when it approves, and paid out on your usual schedule. The fee shows on the
          payment and in your balance transactions.
        </li>
        <li>The provider pays Cutpay the next day. That does not change your balance.</li>
        <li>
          <strong>Refunds</strong> go through the provider, which shortens the customer&apos;s instalments and returns the same share of its fee.
          Refunding a third of a payment gives the customer a third of the price and gives you back a third of the fee, so you give back only
          what you received for it. A provider can refuse a refund, and then the refund fails and the payment is untouched.
        </li>
      </ul>
      <p className="mt-6 text-sm text-muted">
        The list of endpoints is in the <Link to="/docs/api" className="text-brand-700 hover:underline">API reference</Link> under Instalments.
      </p>
    </>
  );
}
