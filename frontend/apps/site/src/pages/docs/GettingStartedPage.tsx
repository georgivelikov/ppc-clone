import { Link } from "react-router";
import { CodeBlock } from "../../components";
import { API_URL, CHECKOUT_URL, DASHBOARD_URL } from "../../links";

const CREATE_PAYMENT = `curl ${API_URL}/v1/payment_intents \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Idempotency-Key: order-1042" \\
  -H "Content-Type: application/json" \\
  -d '{
    "amount": 2500,
    "currency": "EUR",
    "description": "Order 1042",
    "return_url": "https://shop.example.com/thanks?order=1042"
  }'`;

const RESPONSE = `{
  "id": "pay_01J9ZK3Q8V2M6X4N7B5C1D0E9F",
  "object": "payment_intent",
  "livemode": false,
  "amount": 2500,
  "currency": "EUR",
  "status": "requires_payment_method",
  "client_secret": "pay_01J9ZK3Q8V2M6X4N7B5C1D0E9F_secret_...",
  ...
}`;

const SDK = `<div id="card"></div>
<button id="pay">Pay €25.00</button>

<script src="${CHECKOUT_URL}/v1/cutpay.js"></script>
<script>
  const cutpay = Cutpay("pk_test_...");
  const elements = cutpay.elements({ clientSecret });   // from your server
  elements.create("card").mount("#card");

  document.getElementById("pay").onclick = async () => {
    const { error, paymentIntent } = await cutpay.confirmPayment({
      elements,
      returnUrl: "https://shop.example.com/thanks",
    });
    if (error) showMessage(error.message);   // declined, incomplete card, ...
    else showMessage("Thanks! We are confirming your payment.");
  };
</script>`;

const HOSTED = `curl ${API_URL}/v1/checkout_sessions \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Content-Type: application/json" \\
  -d '{"amount": 2500, "currency": "EUR", "return_url": "https://shop.example.com/thanks"}'

# { "payment_id": "pay_...", "client_secret": "...", "url": "${CHECKOUT_URL}/c/pay_..._secret_..." }
# Redirect the customer to "url".`;

export function GettingStartedPage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Getting started</h1>
      <p className="mt-3 text-muted">
        Take your first test payment in a few steps. Everything here runs in <strong>test mode</strong>: no real money, and a
        pretend card network.
      </p>

      <h2 className="mt-10 text-xl font-semibold">1. Create an account and a key</h2>
      <p className="mt-2 text-sm">
        <a href={`${DASHBOARD_URL}/signup`} className="text-brand-700 hover:underline">
          Sign up
        </a>
        , then open <strong>Developers → API keys</strong> and create a <strong>secret key</strong> and a <strong>publishable
        key</strong>. Each is shown once, so copy it straight away.
      </p>
      <ul className="mt-3 list-disc space-y-1 pl-5 text-sm">
        <li>
          The <strong>secret key</strong> (<code>sk_test_…</code>) stays on your server. It can do everything.
        </li>
        <li>
          The <strong>publishable key</strong> (<code>pk_test_…</code>) goes in your web page. It can only be used with the
          card field.
        </li>
        <li>
          A <strong>restricted key</strong> (<code>rk_test_…</code>) can be limited to what one integration needs, such as
          reading payments.
        </li>
      </ul>

      <h2 className="mt-10 text-xl font-semibold">2. Create a payment on your server</h2>
      <p className="mt-2 text-sm">
        Amounts are in the smallest unit, so <code>2500</code> with <code>EUR</code> is €25.00. The <code>Idempotency-Key</code> makes it
        safe to retry: the same key never creates a second payment.
      </p>
      <CodeBlock language="Shell" label="Create a payment" code={CREATE_PAYMENT} />
      <CodeBlock language="JSON" label="Response" code={RESPONSE} />

      <h2 className="mt-10 text-xl font-semibold">3. Collect the card</h2>
      <p className="mt-2 text-sm">Pick one.</p>
      <h3 className="mt-4 font-semibold">On your own page</h3>
      <p className="mt-1 text-sm">
        Give the payment&apos;s <code>client_secret</code> to your page. The card field is an iframe from Cutpay, so the card
        number never reaches your code; you only get the result.
      </p>
      <CodeBlock language="HTML" label="Embedded card field" code={SDK} />
      <h3 className="mt-4 font-semibold">On Cutpay&apos;s page</h3>
      <p className="mt-1 text-sm">
        Create a checkout session instead and redirect the customer to the <code>url</code> you get back. They return to your{" "}
        <code>return_url</code> afterwards.
      </p>
      <CodeBlock language="Shell" label="Hosted checkout" code={HOSTED} />

      <h2 className="mt-10 text-xl font-semibold">4. Find out the payment succeeded</h2>
      <p className="mt-2 text-sm">
        The customer coming back to your site does not prove they paid. Add a webhook endpoint under{" "}
        <strong>Developers → Webhooks</strong>, verify each request&apos;s signature, and fulfil the order when{" "}
        <code>payment_intent.succeeded</code> arrives. The <Link to="/docs/webhooks" className="text-brand-700 hover:underline">webhooks guide</Link>{" "}
        has the details and working code.
      </p>

      <h2 className="mt-10 text-xl font-semibold">5. Try the test cards</h2>
      <p className="mt-2 text-sm">
        Use <code>4242 4242 4242 4242</code> with any future expiry and any CVC. There are cards for declines, 3-D Secure and
        different fee tiers on the <Link to="/docs/test-cards" className="text-brand-700 hover:underline">test cards</Link> page.
      </p>

      <h2 className="mt-10 text-xl font-semibold">What next</h2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          Watch your payments, fees and balance in the dashboard, and follow every API call under{" "}
          <strong>Developers → Logs</strong>.
        </li>
        <li>
          Read the <Link to="/docs/api" className="text-brand-700 hover:underline">API reference</Link> for every endpoint.
        </li>
        <li>Look at the demo shop in the repository for a complete working integration, including webhook verification.</li>
      </ul>
    </>
  );
}
