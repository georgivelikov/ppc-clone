import { Link } from "react-router";
import { CodeBlock } from "../../components";

const EVENT = `{
  "id": "evt_0c7b8d2e-5a1f-4e0b-9a3c-1f6d2b7e4a90",
  "object": "event",
  "type": "payment_intent.succeeded",
  "api_version": "2026-10-01",
  "livemode": false,
  "created_at": "2026-10-17T10:15:02.331Z",
  "data": {
    "object": {
      "id": "pay_01J9ZK3Q8V2M6X4N7B5C1D0E9F",
      "object": "payment_intent",
      "status": "succeeded",
      "amount": 2500,
      "currency": "EUR",
      "customer_email": "anna@example.com",
      "card_brand": "visa"
    }
  }
}`;

const NODE = `import { createHmac, timingSafeEqual } from "node:crypto";

export function verify(secret, rawBody, header, nowSeconds = Math.floor(Date.now() / 1000)) {
  const parts = Object.fromEntries(header.split(",").map((p) => p.trim().split("=", 2)));
  const timestamp = Number(parts.t);
  if (!Number.isInteger(timestamp) || !parts.v1) return false;

  // Reject old requests: this is what stops someone replaying a capture.
  if (Math.abs(nowSeconds - timestamp) > 300) return false;

  const expected = createHmac("sha256", secret).update(\`\${timestamp}.\${rawBody}\`).digest("hex");
  const given = Buffer.from(parts.v1);
  const wanted = Buffer.from(expected);
  return given.length === wanted.length && timingSafeEqual(given, wanted);
}`;

const EXPRESS = `// Use the RAW body. Parsing the JSON and serialising it again changes the bytes and breaks the signature.
app.post("/webhooks/cutpay", express.raw({ type: "application/json" }), (req, res) => {
  const rawBody = req.body.toString("utf8");
  if (!verify(process.env.CUTPAY_WEBHOOK_SECRET, rawBody, req.get("Cutpay-Signature") ?? "")) {
    return res.sendStatus(400);
  }
  const event = JSON.parse(rawBody);
  if (alreadyHandled(event.id)) return res.sendStatus(200);   // events can arrive more than once

  if (event.type === "payment_intent.succeeded") {
    markOrderPaid(event.data.object.id, event.data.object.amount);
  }
  res.sendStatus(200);                                         // answer fast; do slow work afterwards
});`;

const JAVA = `import java.time.Instant;

// The signature code is a single dependency-free class, WebhookSignature.
var result = WebhookSignature.verify(signingSecret, rawBody, request.getHeader("Cutpay-Signature"), Instant.now());
if (result != WebhookSignature.Result.VALID) {
    return ResponseEntity.badRequest().build();
}`;

const EVENTS: [string, string][] = [
  ["payment_intent.created", "A payment was created."],
  ["payment_intent.requires_action", "The customer must complete 3-D Secure."],
  ["payment_intent.amount_capturable_updated", "The amount is held on the card and waits to be captured (requires_capture)."],
  ["payment_intent.succeeded", "The payment was paid. Fulfil the order."],
  ["payment_intent.payment_failed", "The payment failed; the object has a failure_code."],
  ["payment_intent.canceled", "The payment was canceled, or a hold was released (also when it was not captured in time)."],
  ["charge.refunded", "A refund succeeded."],
  ["refund.created", "A refund was started."],
  ["refund.failed", "A refund failed."],
  ["payout.created", "A payout to your bank was created."],
  ["payout.paid", "The money reached your bank."],
  ["payout.failed", "The payout failed; the object has a failure_code such as account_closed."],
  ["payment_link.created", "A payment link was created."],
  ["payment_link.deactivated", "A link closed: you turned it off, it reached max_uses or it expired (see reason)."],
  ["customer.created", "A customer was created."],
  ["customer.updated", "A customer changed."],
  ["customer.deleted", "A customer was deleted."],
  ["payment_method.attached", "A card was saved to a customer."],
  ["payment_method.detached", "A saved card was removed."],
  ["setup_intent.succeeded", "A customer saved a card on the hosted page."],
  ["setup_intent.setup_failed", "Saving the card failed; the object has a failure_code."],
  ["customer.subscription.created", "A subscription started."],
  ["customer.subscription.updated", "A subscription changed: renewed, past due, paused, a new plan or set to end."],
  ["customer.subscription.deleted", "A subscription ended."],
  ["invoice.finalized", "An invoice was created and is open for payment."],
  ["invoice.paid", "An invoice was paid."],
  ["invoice.payment_failed", "A payment for an invoice failed; the object has a failure_code."],
  ["invoice.voided", "An invoice was voided."],
  ["webhook_endpoint.test", "A test event you sent from the dashboard."],
];

export function WebhooksGuidePage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Webhooks</h1>
      <p className="mt-3 text-muted">
        Cutpay tells your server what happened by sending it signed HTTP requests. This is how you learn that a payment
        succeeded, a refund went through or a payout arrived.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Set up an endpoint</h2>
      <ol className="mt-2 list-decimal space-y-1 pl-5 text-sm">
        <li>
          Open <strong>Developers → Webhooks</strong> and choose <strong>Add endpoint</strong>.
        </li>
        <li>Enter the URL of a route on your server and choose the events you want, or all of them.</li>
        <li>
          Copy the <strong>signing secret</strong> (<code>whsec_…</code>). It is shown once, and you need it to check requests.
        </li>
        <li>
          Use <strong>Send test event</strong> to see a request arrive. In test mode the URL may be <code>http://localhost…</code>;
          in live mode it must be https and publicly reachable.
        </li>
      </ol>

      <h2 className="mt-10 text-xl font-semibold">What a request looks like</h2>
      <p className="mt-2 text-sm">
        A <code>POST</code> with a JSON body and these headers: <code>Cutpay-Signature</code>, <code>Cutpay-Event-Id</code> and{" "}
        <code>Content-Type: application/json</code>.
      </p>
      <CodeBlock language="JSON" label="payment_intent.succeeded" code={EVENT} />

      <h2 className="mt-10 text-xl font-semibold">Check the signature</h2>
      <p className="mt-2 text-sm">
        Anyone can send a request to your URL, so check that it really came from Cutpay. The header looks like{" "}
        <code>t=1760000000,v1=53dc…</code>. Compute the HMAC-SHA256 of <code>{"<t>.<raw body>"}</code> with your signing secret and
        compare it with <code>v1</code> in constant time. Also reject a timestamp more than five minutes from now.
      </p>
      <CodeBlock language="JavaScript" label="Verify (Node)" code={NODE} />
      <CodeBlock language="JavaScript" label="In a route (Express)" code={EXPRESS} />
      <CodeBlock language="Java" label="Verify (Java)" code={JAVA} />
      <p className="mt-2 text-sm text-muted">
        A known value to test your code against: with the secret <code>secret</code>, the body <code>{"{}"}</code> and the timestamp{" "}
        <code>1760000000</code>, the signature is <code>53dc054739ad94d3532227ba8d397f66ed2166a6b66940c2006692fa6db6829f</code>.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Answering, and retries</h2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          Answer with any <strong>2xx</strong> status within <strong>10 seconds</strong>. Redirects count as failures.
        </li>
        <li>
          If you answer anything else, or not at all, Cutpay tries again after 1 minute, 5 minutes, 30 minutes, 2 hours, 5
          hours, then every 10 hours, for up to <strong>3 days</strong> after the event.
        </li>
        <li>
          If an endpoint fails continuously for 3 days it is <strong>disabled</strong>. Fix your server, enable it again in the
          dashboard and resend what you missed.
        </li>
        <li>
          Every attempt is logged with your server&apos;s answer. Open a delivery in the dashboard to see it, and{" "}
          <strong>Resend</strong> it whenever you like.
        </li>
      </ul>

      <h2 className="mt-10 text-xl font-semibold">Write a handler that holds up</h2>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          <strong>Expect duplicates.</strong> Delivery is at least once. Remember event <code>id</code>s you have handled.
        </li>
        <li>
          <strong>Expect any order.</strong> A refund event can arrive before the payment event. Use <code>created_at</code> or
          fetch the object from the API when order matters.
        </li>
        <li>
          <strong>Check the amount</strong> against your own order before marking it paid.
        </li>
        <li>
          <strong>Answer first, work later.</strong> Acknowledge quickly and do slow work in the background.
        </li>
      </ul>

      <h2 className="mt-10 text-xl font-semibold">Event types</h2>
      <div className="mt-3 overflow-x-auto rounded-card border border-border bg-surface">
        <table className="w-full text-left text-sm">
          <tbody>
            {EVENTS.map(([type, text]) => (
              <tr key={type} className="border-b border-border last:border-0">
                <td className="px-4 py-2.5 font-mono text-xs whitespace-nowrap">{type}</td>
                <td className="px-4 py-2.5 text-muted">{text}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-4 text-sm text-muted">
        Subscribe to a whole family with a wildcard such as <code>payout.*</code>. The full shape of every object is in the{" "}
        <Link to="/docs/api" className="text-brand-700 hover:underline">API reference</Link>.
      </p>
    </>
  );
}
