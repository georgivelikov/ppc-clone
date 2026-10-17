import { Link } from "react-router";
import { PageHeader, Section } from "../components";

export const FAQ: { question: string; answer: React.ReactNode }[] = [
  {
    question: "Is this real money?",
    answer: "No. Cutpay is an educational project with a simulated card network and bank. Nothing you enter is charged, so never type a real card number.",
  },
  {
    question: "What does it cost?",
    answer: (
      <>
        1.29% + €0.10 for a European consumer card and 2.69% + €0.10 for business and non-European cards. Scheduled payouts are
        free and an instant payout costs 1%. See <Link to="/pricing" className="text-brand-700 hover:underline">pricing</Link>.
      </>
    ),
  },
  {
    question: "How long until I am paid?",
    answer:
      "A successful payment is pending for two days by default (you can change this from 0 to 30 days), then available. Available funds go to your bank on your payout schedule: daily, weekly or whenever you choose.",
  },
  {
    question: "What is the difference between test mode and live mode?",
    answer:
      "They are two separate worlds. Test keys only see test payments, test balances and test payouts, and live keys only see live ones. Use test mode to build and check your integration, with the test cards from the docs.",
  },
  {
    question: "Do card numbers touch my server?",
    answer:
      "Not if you use the card field or the hosted page. Both run on Cutpay's domain; your server only creates the payment and learns the result, from the payment status and from webhooks.",
  },
  {
    question: "How do I know a customer paid?",
    answer: (
      <>
        Listen for the <code className="rounded bg-canvas px-1 text-xs">payment_intent.succeeded</code> webhook and check its
        signature. Do not rely only on the browser being sent back to your site. See the{" "}
        <Link to="/docs/webhooks" className="text-brand-700 hover:underline">webhooks guide</Link>.
      </>
    ),
  },
  {
    question: "What happens if my server is down when a webhook is sent?",
    answer:
      "Cutpay retries after 1 minute, 5 minutes, 30 minutes, 2 hours, 5 hours and then every 10 hours for up to three days. You can also resend any delivery by hand from the dashboard. An endpoint that fails for three days in a row is switched off, and you can switch it back on.",
  },
  {
    question: "Can I take a payment twice by accident?",
    answer:
      "Not if you send an Idempotency-Key. Repeating a request with the same key returns the first response instead of creating a second payment.",
  },
  {
    question: "Can I refund part of a payment?",
    answer: "Yes, as many times as you like until the whole amount has been returned. The fee on the original payment is not refunded.",
  },
  {
    question: "Which currencies are supported?",
    answer: "EUR, RON, PLN, HUF, CZK, GBP, USD and CHF.",
  },
];

export function FaqPage() {
  return (
    <>
      <PageHeader title="Frequently asked questions" intro="Quick answers about fees, payouts and how the integration works." />
      <Section title="Questions">
        <div className="max-w-3xl divide-y divide-border rounded-card border border-border bg-surface">
          {FAQ.map((item) => (
            <details key={item.question} className="group px-4 py-3">
              <summary className="cursor-pointer list-none font-medium marker:hidden">{item.question}</summary>
              <div className="mt-2 text-sm text-muted">{item.answer}</div>
            </details>
          ))}
        </div>
      </Section>
    </>
  );
}
