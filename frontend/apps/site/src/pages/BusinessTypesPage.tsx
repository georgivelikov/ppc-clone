import { Card } from "@cutpay/ui";
import { Coffee, Palette, Repeat, ShoppingBag, Store, Wrench, type LucideIcon } from "lucide-react";
import { Link } from "react-router";
import { PageHeader, Section } from "../components";

const TYPES: { icon: LucideIcon; name: string; text: string }[] = [
  {
    icon: ShoppingBag,
    name: "Online shops",
    text: "Create a payment when the cart is checked out, show the card field in your page, and mark the order paid when the webhook arrives.",
  },
  {
    icon: Coffee,
    name: "Cafes and restaurants",
    text: "Take a payment for a table or a pre-order with a link or QR code, and see the day's takings against your payouts.",
  },
  {
    icon: Repeat,
    name: "Software and subscriptions",
    text: "Charge for access with a hosted checkout, handle failed payments through events, and refund a month in a click.",
  },
  {
    icon: Wrench,
    name: "Service businesses",
    text: "Send a payment link after the job, or take the card over the phone. Refund the difference if the price changes.",
  },
  {
    icon: Palette,
    name: "Creators and makers",
    text: "A simple page, a link in your bio and payouts to your bank. The demo shop shows the whole flow in a few hundred lines.",
  },
  {
    icon: Store,
    name: "Markets and pop-ups",
    text: "Take cards from a phone browser at the stall. Fees are shown per payment, so the evening's maths is easy.",
  },
];

export function BusinessTypesPage() {
  return (
    <>
      <PageHeader title="Built for how you sell" intro="The same few building blocks fit very different businesses." />
      <Section title="Who uses Cutpay">
        <ul className="grid gap-4 sm:grid-cols-2">
          {TYPES.map((type) => (
            <li key={type.name}>
              <Card className="h-full">
                <type.icon className="size-6 text-brand-600" aria-hidden />
                <h2 className="mt-3 font-semibold">{type.name}</h2>
                <p className="mt-1 text-sm text-muted">{type.text}</p>
              </Card>
            </li>
          ))}
        </ul>
        <p className="mt-8 text-sm text-muted">
          Not sure it fits? Read the <Link to="/docs" className="text-brand-700 hover:underline">getting started guide</Link>; most
          people have a test payment working in about ten minutes.
        </p>
      </Section>
    </>
  );
}
