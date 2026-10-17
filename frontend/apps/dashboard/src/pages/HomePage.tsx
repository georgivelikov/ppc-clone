import { Badge, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { useTestMode } from "../layout/testMode";

const STEPS = [
  { title: "Take your first payment", text: "Create a test payment on the Payments page and pay it with 4242 4242 4242 4242." },
  { title: "See your balance", text: "Check Balances for fees and what is pending, and Payouts for what reaches your bank." },
  { title: "Connect your website", text: "Create API keys and a webhook endpoint under Developers, then add the checkout SDK to your site." },
];

export function HomePage() {
  const { merchant, user } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const firstName = user.full_name.split(" ")[0];
  const summary = useQuery({ queryKey: ["payment-summary", livemode], queryFn: () => api.paymentSummary(livemode) });
  const today = summary.data?.today.find((row) => row.currency === merchant.default_currency);

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
          <p className="mt-2 text-2xl font-semibold" data-testid="today-volume">
            {formatMoney(today?.volume ?? 0, merchant.default_currency)}
          </p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Payments today</p>
          <p className="mt-2 text-2xl font-semibold" data-testid="today-count">
            {today?.count ?? 0}
          </p>
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
