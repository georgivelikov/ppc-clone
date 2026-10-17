import { Badge, Card } from "@cutpay/ui";
import { Banknote, CreditCard, Link2, ScanLine, Store, type LucideIcon } from "lucide-react";
import { Link } from "react-router";
import { PageHeader, Section } from "../components";

interface Product {
  icon: LucideIcon;
  name: string;
  text: string;
  points: string[];
  available: boolean;
  link?: { to: string; label: string };
}

const PRODUCTS: Product[] = [
  {
    icon: CreditCard,
    name: "Checkout",
    text: "A hosted payment page and an embeddable card field, both with 3-D Secure and a clear receipt.",
    points: ["Hosted page or card field in your own page", "3-D Secure handled for you", "Refunds, in full or in part"],
    available: true,
    link: { to: "/docs", label: "Start with checkout" },
  },
  {
    icon: Banknote,
    name: "Billing & payouts",
    text: "See what is pending and what is available, then get paid on a daily, weekly or manual schedule.",
    points: ["Every fee itemised per payment", "Scheduled payouts, or pay out now for 1%", "Reconciliation of each payout to its payments"],
    available: true,
    link: { to: "/pricing", label: "See the fees" },
  },
  {
    icon: Link2,
    name: "Links & QR",
    text: "Share a payment link or print a QR code. No website needed.",
    points: ["Links for a fixed or open amount", "A QR code for every link", "Works on any phone"],
    available: false,
  },
  {
    icon: Store,
    name: "Virtual POS",
    text: "Take card payments over the phone or at the counter from a browser.",
    points: ["Key in a card, or send the customer a link", "Same fees and payouts as online", "A simple daily summary"],
    available: false,
  },
  {
    icon: ScanLine,
    name: "Buy now, pay later",
    text: "Offer instalments from several providers behind one integration.",
    points: ["One integration, several providers", "Offers chosen by amount and country", "You are paid up front"],
    available: false,
  },
];

export function ProductsPage() {
  return (
    <>
      <PageHeader title="Products" intro="Start with checkout and payouts today. The rest of the toolkit is on its way." />
      <Section title="What you can use">
        <ul className="grid gap-4 md:grid-cols-2">
          {PRODUCTS.map((product) => (
            <li key={product.name}>
              <Card className="flex h-full flex-col">
                <div className="flex items-center justify-between">
                  <product.icon className="size-6 text-brand-600" aria-hidden />
                  <Badge tone={product.available ? "success" : "neutral"}>{product.available ? "Available" : "Coming soon"}</Badge>
                </div>
                <h2 className="mt-3 text-lg font-semibold">{product.name}</h2>
                <p className="mt-1 text-sm text-muted">{product.text}</p>
                <ul className="mt-3 flex-1 list-disc space-y-1 pl-5 text-sm">
                  {product.points.map((point) => (
                    <li key={point}>{point}</li>
                  ))}
                </ul>
                {product.link && (
                  <Link to={product.link.to} className="mt-4 text-sm font-medium text-brand-700 hover:underline">
                    {product.link.label} →
                  </Link>
                )}
              </Card>
            </li>
          ))}
        </ul>
      </Section>
    </>
  );
}
