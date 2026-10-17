import { Button, Card } from "@cutpay/ui";
import { Code2, Landmark, ShieldCheck, Zap } from "lucide-react";
import { Link } from "react-router";
import { CodeBlock, Section } from "../components";
import { SIGNUP_URL } from "../links";

const SNIPPET = `const cutpay = Cutpay("pk_test_…");
const elements = cutpay.elements({ clientSecret });
elements.create("card").mount("#card");

const { error, paymentIntent } = await cutpay.confirmPayment({ elements });`;

const POINTS = [
  {
    icon: Zap,
    title: "Take a payment in an afternoon",
    text: "Send customers to a hosted page, or put the card field inside your own page with a few lines of JavaScript.",
  },
  {
    icon: ShieldCheck,
    title: "Card numbers never touch your server",
    text: "The card field runs on our domain inside an iframe. You only ever receive the result of the payment.",
  },
  {
    icon: Landmark,
    title: "Know exactly what you will get",
    text: "Every fee is itemised. Pending and available balances are always visible, and payouts reconcile to the cent.",
  },
  {
    icon: Code2,
    title: "Built for developers",
    text: "Test and live modes, API keys with scopes, signed webhooks that retry for three days, and a request log.",
  },
];

export function HomePage() {
  return (
    <>
      <section className="bg-surface">
        <div className="mx-auto grid max-w-6xl items-center gap-10 px-4 py-20 md:grid-cols-2">
          <div>
            <p className="text-sm font-semibold text-brand-700">Payments for growing businesses</p>
            <h1 className="mt-3 text-5xl leading-tight font-semibold tracking-tight">Get paid by card, without the headaches.</h1>
            <p className="mt-5 max-w-lg text-lg text-muted">
              Cutpay lets you accept card payments, follow every cent through your balance, and get paid out to your bank on
              a schedule you choose.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <a href={SIGNUP_URL}>
                <Button>Create a free account</Button>
              </a>
              <Link to="/docs">
                <Button variant="secondary">Read the docs</Button>
              </Link>
            </div>
            <p className="mt-4 text-sm text-muted">No setup fee. You pay only when you get paid.</p>
          </div>
          <CodeBlock language="JavaScript" label="Accept a card on your own page" code={SNIPPET} />
        </div>
      </section>

      <Section title="Everything between the customer and your bank">
        <ul className="grid gap-4 sm:grid-cols-2">
          {POINTS.map((point) => (
            <li key={point.title}>
              <Card className="h-full">
                <point.icon className="size-6 text-brand-600" aria-hidden />
                <h3 className="mt-3 font-semibold">{point.title}</h3>
                <p className="mt-1 text-sm text-muted">{point.text}</p>
              </Card>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="How it works" className="border-t border-border">
        <ol className="grid gap-6 md:grid-cols-3">
          {[
            ["1", "Create your account", "Sign up, and you are in test mode straight away with test keys and a sandbox bank."],
            ["2", "Connect your site", "Create an API key, create a payment on your server, and show the card field or redirect to checkout."],
            ["3", "Get paid", "Funds appear as pending, become available after a short delay, and are paid out to your bank."],
          ].map(([number, title, text]) => (
            <li key={number}>
              <span className="grid size-8 place-items-center rounded-full bg-brand-50 font-semibold text-brand-700">{number}</span>
              <h3 className="mt-3 font-semibold">{title}</h3>
              <p className="mt-1 text-sm text-muted">{text}</p>
            </li>
          ))}
        </ol>
      </Section>

      <section className="bg-brand-600 text-white">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-4 px-4 py-12">
          <div>
            <h2 className="text-2xl font-semibold">Try it with test cards</h2>
            <p className="mt-1 text-white/80">Everything works in test mode, with fake money and a pretend bank.</p>
          </div>
          <Link to="/docs/test-cards" className="rounded-lg bg-white px-4 py-2 text-sm font-medium text-brand-700 hover:bg-brand-50">
            See the test cards
          </Link>
        </div>
      </section>
    </>
  );
}
