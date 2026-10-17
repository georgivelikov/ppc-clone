import { Badge, Card } from "@cutpay/ui";
import { useSession } from "../auth/AuthProvider";

const STEPS = [
  { title: "Take your first payment", text: "Hosted checkout and test cards arrive in phase 1." },
  { title: "See your balance", text: "Fees, balances and payouts arrive in phase 2." },
  { title: "Connect your website", text: "API keys, webhooks and the checkout SDK arrive in phase 3." },
];

export function HomePage() {
  const { merchant, user } = useSession();
  const firstName = user.full_name.split(" ")[0];

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Welcome, {firstName}</h1>
        <p className="mt-1 text-sm text-muted">
          {merchant.display_name} is set up in {merchant.country} and settles in {merchant.default_currency}.{" "}
          <Badge tone="warning">{merchant.status.replace("_", " ")}</Badge>
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <Card>
          <p className="text-sm text-muted">Today&apos;s volume</p>
          <p className="mt-2 text-2xl font-semibold">{merchant.default_currency} 0.00</p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Payments</p>
          <p className="mt-2 text-2xl font-semibold">0</p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Next payout</p>
          <p className="mt-2 text-2xl font-semibold">None yet</p>
        </Card>
      </div>

      <Card>
        <h2 className="font-semibold">Getting started</h2>
        <ol className="mt-4 space-y-4">
          {STEPS.map((step, index) => (
            <li key={step.title} className="flex gap-3">
              <span className="grid size-6 shrink-0 place-items-center rounded-full bg-canvas text-xs font-medium text-muted">
                {index + 1}
              </span>
              <div>
                <p className="text-sm font-medium">{step.title}</p>
                <p className="text-sm text-muted">{step.text}</p>
              </div>
            </li>
          ))}
        </ol>
      </Card>
    </div>
  );
}
