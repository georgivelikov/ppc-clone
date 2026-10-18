import { Link } from "react-router";
import { CodeBlock } from "../../components";

const SAVE_CARD = `curl http://localhost:8080/v1/customers \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "name": "Anna Petrova", "email": "anna@example.com" }'

curl http://localhost:8080/v1/setup_intents \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "customer": "cus_01J9ZK3Q8V2M6X4N7B5C1D0E9F" }'

# Send the customer to the "url" of the answer. They enter their card; you are not charged and they are not either.`;

const OFF_SESSION = `curl http://localhost:8080/v1/payment_intents \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{
    "amount": 1900,
    "currency": "EUR",
    "customer": "cus_01J9ZK3Q8V2M6X4N7B5C1D0E9F",
    "off_session": true
  }'`;

const PREAUTH = `# 1. Hold the money when the guest checks in
curl http://localhost:8080/v1/payment_intents \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "amount": 20000, "currency": "EUR", "capture_method": "manual" }'

# 2. When they leave, take what they used. The rest goes back to their card.
curl -X POST http://localhost:8080/v1/payment_intents/pay_01J9.../capture \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "amount_to_capture": 15000 }'`;

const SUBSCRIBE = `curl http://localhost:8080/v1/products \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "name": "Coffee club" }'

curl http://localhost:8080/v1/prices \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "product": "prod_...", "unit_amount": 1500, "currency": "EUR", "interval": "month", "trial_days": 7 }'

curl http://localhost:8080/v1/subscriptions \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "customer": "cus_...", "items": [{ "price": "price_...", "quantity": 1 }] }'`;

export function BillingPage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Saved cards, pre-authorisation, subscriptions and invoices</h1>
      <p className="mt-3 text-muted">
        Charge the same customer again without asking for their card, hold money and take it later, and bill every month.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Customers and saved cards</h2>
      <p className="mt-2 text-sm">
        A <strong>customer</strong> groups a person&apos;s cards, payments, subscriptions and invoices. A card is saved with a{" "}
        <strong>setup intent</strong>: a hosted page where the customer enters it without being charged. The card number goes to
        the vault; Cutpay keeps only the brand, the last four digits and the expiry.
      </p>
      <CodeBlock language="Shell" label="Save a card" code={SAVE_CARD} />
      <p className="mt-2 text-sm">
        You can also save the card a customer pays with at checkout: create the payment with a <code>customer</code> and{" "}
        <code>setup_future_usage: &quot;off_session&quot;</code>, and the checkout tells the customer their card will be kept.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Charging without the customer</h2>
      <p className="mt-2 text-sm">
        A payment with <code>off_session: true</code> is charged to the saved card straight away. The bank cannot ask the customer
        to authenticate because nobody is there, so what happens depends on the card:
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          A card that needs 3-D Secure (<code>4000 0000 0000 3220</code>) is challenged once, when it is saved. Afterwards it can be charged
          without the customer.
        </li>
        <li>
          A card that needs it every time (<code>4000 0027 6000 3184</code>) fails with <code>authentication_required</code>. The customer has to
          come back and pay on a page.
        </li>
        <li>
          A card that was fine when saved but is declined later (<code>4000 0000 0000 0341</code>) fails with <code>card_declined</code>.
        </li>
      </ul>
      <CodeBlock language="Shell" label="Charge a saved card" code={OFF_SESSION} />

      <h2 className="mt-10 text-xl font-semibold">Pre-authorisation and capture</h2>
      <p className="mt-2 text-sm">
        With <code>capture_method: &quot;manual&quot;</code> the customer&apos;s card is only <strong>held</strong>: the payment waits in{" "}
        <code>requires_capture</code> and you receive <code>payment_intent.amount_capturable_updated</code>. Capture all of it or part of it;
        what is not captured is released. A hold nobody captures within <strong>seven days</strong> is released automatically and the payment
        is canceled. Hotels and rentals use this to reserve the cost of a stay and charge what was used.
      </p>
      <CodeBlock language="Shell" label="Hold, then capture" code={PREAUTH} />
      <p className="mt-2 text-sm text-muted">
        Fees and your balance only see the captured amount. A refund can be at most that amount too. You can also hold a card at the{" "}
        <Link to="/docs/payment-links" className="text-brand-600 hover:underline">
          virtual terminal
        </Link>{" "}
        with the &quot;Pre-authorise only&quot; option.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Subscriptions</h2>
      <p className="mt-2 text-sm">
        A <strong>product</strong> has <strong>prices</strong>. A price with an <code>interval</code> recurs. A price never changes: to
        charge something else, archive it and add a new one, so existing subscriptions keep their terms.
      </p>
      <CodeBlock language="Shell" label="Start a subscription" code={SUBSCRIBE} />
      <ul className="mt-3 list-disc space-y-1 pl-5 text-sm">
        <li>
          <strong>Billed in advance.</strong> The first invoice is created when the subscription starts, or when its trial ends. Each period
          is invoiced as it begins. Periods are counted from the first day, so a subscription that started on the 31st is billed on the
          28th in February and on the 31st again in March.
        </li>
        <li>
          <strong>Trials.</strong> A price can have trial days, or you can set them per subscription. Nothing is charged during the trial and
          you need a saved card to start it.
        </li>
        <li>
          <strong>Statuses.</strong> <code>trialing</code>, <code>active</code>, <code>past_due</code> (a payment failed, retries pending),{" "}
          <code>unpaid</code>, <code>canceled</code> and <code>incomplete</code> (the first payment failed).
        </li>
        <li>
          <strong>Cancel, pause.</strong> Cancel now or at the end of the period. Pausing stops new invoices; resuming starts a new period if
          the paid one ran out.
        </li>
      </ul>

      <h3 className="mt-8 text-lg font-semibold">Changing plan</h3>
      <p className="mt-2 text-sm">
        When a customer upgrades in the middle of a period, they already paid for the old plan. Cutpay credits the unused part of it and
        charges the rest of the period at the new plan, both worked out from the seconds left and rounded half up. Halfway through a 31-day
        month, going from €10 to €30 gives a credit of €5 and a charge of €15, so €10 net. The lines join the next invoice, or are charged
        at once with <code>bill_immediately</code>. <code>POST /v1/subscriptions/&#123;id&#125;/proration_preview</code> shows them before you
        decide. A change during a trial is not prorated.
      </p>

      <h3 className="mt-8 text-lg font-semibold">When a payment fails</h3>
      <p className="mt-2 text-sm">
        The subscription becomes <code>past_due</code> and the invoice is tried again <strong>1, 3, 5 and 7 days</strong> after the first
        failure (change this with <code>PUT /v1/billing_settings</code>). The customer is emailed each time, with a link to pay the invoice
        themselves. When the last retry fails the subscription is canceled, or kept as <code>unpaid</code> if you prefer, and the invoice is
        marked <code>uncollectible</code>. If a retry succeeds the subscription is <code>active</code> again and the remaining retries are
        dropped.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Invoices</h2>
      <p className="mt-2 text-sm">
        Subscriptions create their invoices. You can also send a <strong>one-off invoice</strong>: create it with lines (a negative amount
        is a discount) and an optional tax rate, then <em>finalize</em> it to give it a number such as <code>INV-0001</code> and email the
        customer a link. The link opens a page where they see the invoice, download the PDF and pay it with a card. Or charge the saved card
        yourself with <code>POST /v1/invoices/&#123;id&#125;/pay</code>.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Events</h2>
      <p className="mt-2 text-sm">
        <code>customer.created</code>, <code>customer.updated</code>, <code>customer.deleted</code>, <code>payment_method.attached</code>,{" "}
        <code>payment_method.detached</code>, <code>setup_intent.succeeded</code>, <code>setup_intent.setup_failed</code>,{" "}
        <code>customer.subscription.created</code>, <code>customer.subscription.updated</code>, <code>customer.subscription.deleted</code>,{" "}
        <code>invoice.finalized</code>, <code>invoice.paid</code>, <code>invoice.payment_failed</code> and <code>invoice.voided</code>. See{" "}
        <Link to="/docs/webhooks" className="text-brand-600 hover:underline">
          Webhooks
        </Link>
        .
      </p>
    </>
  );
}
