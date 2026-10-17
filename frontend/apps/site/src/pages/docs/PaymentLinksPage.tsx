import { Link } from "react-router";
import { CodeBlock } from "../../components";

const CREATE = `curl http://localhost:8080/v1/payment_links \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Idempotency-Key: $(uuidgen)" \\
  -H "Content-Type: application/json" \\
  -d '{
    "type": "fixed",
    "amount": 1500,
    "currency": "EUR",
    "title": "Table 4",
    "max_uses": 1,
    "collect": ["email"],
    "success_message": "Thank you! See you soon."
  }'`;

const RESPONSE = `{
  "id": "plink_01J9ZK3Q8V2M6X4N7B5C1D0E9F",
  "object": "payment_link",
  "url": "http://localhost:5174/l/9GkTq2XwQm",
  "status": "active",
  "type": "fixed",
  "amount": 1500,
  "currency": "EUR",
  "max_uses": 1,
  "uses_count": 0,
  "amount_collected": 0,
  ...
}`;

const STREAM = `curl -N http://localhost:8080/v1/payment_intents/pay_01J9.../stream \\
  -H "Authorization: Bearer sk_test_..." \\
  -H "Accept: text/event-stream"

event:status
data:{"status":"requires_payment_method"}

event:status
data:{"status":"succeeded"}`;

export function PaymentLinksPage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Payment links, QR codes and the virtual terminal</h1>
      <p className="mt-3 text-muted">
        Take payments without building a website: send a link in a chat, print a QR code for the table, or key in a card number
        taken over the phone.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Payment links</h2>
      <p className="mt-2 text-sm">
        A link opens a Cutpay page where the customer pays. Create one in the dashboard under <strong>Links</strong>, or with the API.
        There are three kinds:
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
        <li>
          <strong>Fixed price</strong>: one amount. Optionally let the customer change the quantity.
        </li>
        <li>
          <strong>Customer chooses</strong>: the customer types the amount, within the minimum and maximum you set. Good for tips and
          donations.
        </li>
        <li>
          <strong>Products</strong>: a list of items that add up to the total.
        </li>
      </ul>
      <CodeBlock language="Shell" label="Create a link" code={CREATE} />
      <CodeBlock language="JSON" label="Response" code={RESPONSE} />
      <p className="mt-2 text-sm">
        Share the <code>url</code>. The part after <code>/l/</code> is ten random characters, so a link cannot be guessed. You can also
        ask the customer for their email, name, phone number or address before they pay; what they type is saved in the payment&apos;s{" "}
        <code>metadata</code> and email.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Limiting uses</h2>
      <p className="mt-2 text-sm">
        Set <code>max_uses</code> to sell a limited number of something: a ticket, the last slot of a workshop. When the link is
        opened for payment it <strong>holds one use</strong> for that customer for 15 minutes. If two customers open the last use
        at the same moment, exactly one of them gets it and the other is told the link is no longer available. The held use becomes a real
        one when the payment succeeds, and goes back when the payment fails, is canceled or the 15 minutes pass. After the last use
        is paid the link closes by itself and sends a <code>payment_link.deactivated</code> event.
      </p>

      <h2 className="mt-10 text-xl font-semibold">QR codes and table cards</h2>
      <p className="mt-2 text-sm">
        Every link has a QR code. Open the link in the dashboard to change its colours and download it as <strong>SVG</strong>{" "}
        (sharp at any size), <strong>PNG</strong> or a print-ready <strong>A6 table card</strong> (PDF). With the API use{" "}
        <code>GET /v1/payment_links/{"{id}"}/qr</code> and <code>/card.pdf</code>. Keep strong contrast between the code and its
        background, or phones may not read it. To test on your phone, open the dashboard and checkout using your computer&apos;s
        address on the local network instead of <code>localhost</code>.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Charge with QR</h2>
      <p className="mt-2 text-sm">
        For a counter or a market stall, open <strong>Terminal → Charge with QR</strong>, type the amount and show the full-screen
        code to your customer. The screen watches the payment and turns into a big green tick as soon as it is paid, usually within a
        second. Behind it is a stream you can use yourself:
      </p>
      <CodeBlock language="Shell" label="Follow a payment live" code={STREAM} />
      <p className="mt-2 text-sm text-muted">
        For anything that matters, such as marking an order paid, rely on the webhook. The stream is for screens.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Virtual terminal</h2>
      <p className="mt-2 text-sm">
        <strong>Terminal → Virtual POS</strong> lets you key in an order taken by phone or mail. You enter the amount, then the
        customer reads out their card, which you type into the same secure card field customers see at checkout: the number goes
        straight to Cutpay and never touches the dashboard&apos;s code. The payment has <code>channel: &quot;moto&quot;</code>.
      </p>
      <p className="mt-2 text-sm">
        <strong>Why there is no 3-D Secure step.</strong> 3-D Secure asks the cardholder to prove they are the one paying, usually in
        their banking app. When you type a card in yourself, the cardholder is not at a screen to answer. Card payments taken by
        mail or telephone (MOTO) are outside the European strong customer authentication rules, so the bank does not ask for it. The
        trade-off is that these payments are easier to dispute, which is why they are limited to signed-in team members and are not
        available to API keys. Try it with the 3-D Secure test card <code>4000 0000 0000 3220</code>: at the virtual terminal it is
        approved straight away, while at checkout it asks for a code.
      </p>
      <p className="mt-2 text-sm">
        When the payment succeeds you get a printable receipt. See{" "}
        <Link to="/docs/test-cards" className="text-brand-600 hover:underline">
          Test cards
        </Link>{" "}
        for numbers to try.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Events</h2>
      <p className="mt-2 text-sm">
        Payments made through a link are ordinary payments, so <code>payment_intent.succeeded</code> works as usual and the payment&apos;s{" "}
        <code>metadata</code> carries <code>link_id</code> and <code>link_slug</code>. Links also send{" "}
        <code>payment_link.created</code> and <code>payment_link.deactivated</code> (with a <code>reason</code> of{" "}
        <code>manual</code>, <code>max_uses_reached</code> or <code>expired</code>).
      </p>
    </>
  );
}
