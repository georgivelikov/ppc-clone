import { ApiError, type SubscriptionStatus } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney, Input, Label } from "@cutpay/ui";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { api } from "../../api";
import { SUBSCRIPTION_LABEL, SubscriptionStatusBadge } from "../../components/BillingBadges";
import { ChurnChart } from "../../components/ChurnChart";
import { useTestMode } from "../../layout/testMode";
import { intervalLabel } from "../../lib/billing";

const PAGE_SIZE = 20;

/** Monthly recurring revenue and subscriber counts. Trials are not revenue yet. */
function Figures({ livemode }: { livemode: boolean }) {
  const stats = useQuery({ queryKey: ["subscription-stats", livemode], queryFn: () => api.subscriptionStats(livemode) });
  const mrr = Object.entries(stats.data?.mrr ?? {});
  const count = (status: SubscriptionStatus) => stats.data?.by_status[status] ?? 0;
  // Churn is measured among paying subscribers; a trial that ends has not paid yet.
  const live = count("active") + count("past_due");
  const churn = live + (stats.data?.ended_last_30_days ?? 0) > 0
    ? ((stats.data?.ended_last_30_days ?? 0) / (live + (stats.data?.ended_last_30_days ?? 0))) * 100
    : 0;

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-label="Subscription figures">
      <Card>
        <p className="text-sm text-muted">Monthly recurring revenue</p>
        <p className="mt-2 text-2xl font-semibold" data-testid="mrr">
          {mrr.length === 0 ? "—" : mrr.map(([currency, amount]) => formatMoney(amount, currency)).join(" · ")}
        </p>
      </Card>
      <Card>
        <p className="text-sm text-muted">Active</p>
        <p className="mt-2 text-2xl font-semibold" data-testid="count-active">
          {count("active")}
        </p>
      </Card>
      <Card>
        <p className="text-sm text-muted">Trials · Past due</p>
        <p className="mt-2 text-2xl font-semibold" data-testid="count-trial-past-due">
          {count("trialing")} · {count("past_due")}
        </p>
      </Card>
      <Card>
        <p className="text-sm text-muted">Churn, last 30 days</p>
        <p className="mt-2 text-2xl font-semibold" data-testid="churn">
          {churn.toFixed(1)}%
        </p>
        <p className="text-xs text-muted">{stats.data?.ended_last_30_days ?? 0} ended</p>
      </Card>
      <div className="sm:col-span-2 lg:col-span-4">
        <ChurnChart months={stats.data?.churn_by_month ?? []} />
      </div>
    </div>
  );
}

/** When unpaid invoices are retried and what happens after the last retry. */
function RetryRules() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: ["billing-settings"], queryFn: () => api.getBillingSettings() });
  const [days, setDays] = useState("");
  const [finalAction, setFinalAction] = useState<"cancel" | "unpaid">("cancel");
  const [problem, setProblem] = useState<string | null>(null);
  useEffect(() => {
    if (settings.data) {
      setDays(settings.data.retry_days.join(", "));
      setFinalAction(settings.data.final_action);
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: (retry_days: number[]) => api.updateBillingSettings({ retry_days, final_action: finalAction }),
    onSuccess: (saved) => queryClient.setQueryData(["billing-settings"], saved),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const parsed = days.split(",").map((d) => d.trim()).filter(Boolean).map(Number);
    if (parsed.length === 0 || parsed.some((d) => !Number.isInteger(d) || d < 1 || d > 60) || parsed.some((d, i) => i > 0 && d <= parsed[i - 1]!)) {
      setProblem("Enter increasing whole days from 1 to 60, like 1, 3, 5, 7");
      return;
    }
    setProblem(null);
    save.mutate(parsed);
  }

  return (
    <Card>
      <h2 className="font-semibold">When a payment fails</h2>
      <p className="mt-1 text-sm text-muted">
        The invoice is tried again on these days after the first failure. The customer is emailed each time.
      </p>
      <form onSubmit={submit} noValidate className="mt-4 grid gap-4 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
        <div>
          <Label htmlFor="retry-days">Retry on day</Label>
          <Input id="retry-days" value={days} onChange={(e) => setDays(e.target.value)} aria-invalid={Boolean(problem)} />
        </div>
        <div>
          <Label htmlFor="final-action">After the last retry fails</Label>
          <select id="final-action" value={finalAction} onChange={(e) => setFinalAction(e.target.value as "cancel" | "unpaid")} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm">
            <option value="cancel">Cancel the subscription</option>
            <option value="unpaid">Mark it unpaid</option>
          </select>
        </div>
        <Button type="submit" variant="secondary" loading={save.isPending}>
          Save rules
        </Button>
      </form>
      {problem && (
        <p role="alert" className="mt-2 text-sm text-danger">
          {problem}
        </p>
      )}
      {save.isError && <Alert className="mt-3">{save.error instanceof ApiError ? save.error.message : "Could not save."}</Alert>}
      {save.isSuccess && (
        <p role="status" className="mt-2 text-sm text-success">
          Saved.
        </p>
      )}
    </Card>
  );
}

export function SubscriptionsPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [status, setStatus] = useState<SubscriptionStatus | "">("");
  const query = useInfiniteQuery({
    queryKey: ["subscriptions", { livemode, status }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listSubscriptions({ status, starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const subscriptions = query.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Subscriptions</h1>
          <p className="mt-1 text-sm text-muted">Recurring payments from your customers.</p>
        </div>
        <Link to="/subscriptions/new">
          <Button>
            <Plus className="size-4" aria-hidden />
            New subscription
          </Button>
        </Link>
      </div>

      <Figures livemode={livemode} />

      <select aria-label="Filter by status" value={status} onChange={(e) => setStatus(e.target.value as SubscriptionStatus | "")} className="h-10 rounded-lg border border-border bg-surface px-3 text-sm">
        <option value="">All statuses</option>
        {Object.entries(SUBSCRIPTION_LABEL).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Customer</th>
              <th className="px-4 py-3 font-medium">Plan</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 text-right font-medium">Amount</th>
              <th className="px-4 py-3 font-medium">Next invoice</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {subscriptions.map((sub) => (
              <tr key={sub.id} className="hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/subscriptions/${sub.id}`} className="font-medium text-brand-700 hover:underline">
                    {sub.customer_name ?? sub.customer_email ?? sub.customer}
                  </Link>
                  <p className="text-xs text-muted">{sub.id}</p>
                </td>
                <td className="px-4 py-3">{sub.items.map((item) => `${item.product_name}${item.quantity > 1 ? ` × ${item.quantity}` : ""}`).join(", ")}</td>
                <td className="px-4 py-3">
                  <SubscriptionStatusBadge status={sub.status} />
                </td>
                <td className="px-4 py-3 text-right tabular-nums">
                  {formatMoney(sub.items.reduce((sum, item) => sum + item.amount, 0), sub.currency)}
                  <span className="text-muted"> {intervalLabel(sub.interval, sub.interval_count)}</span>
                </td>
                <td className="px-4 py-3 text-muted">
                  {sub.status === "canceled" ? "—" : new Date(sub.current_period_end).toLocaleDateString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.isPending && <p className="p-6 text-sm text-muted">Loading…</p>}
        {query.isError && (
          <p className="p-6 text-sm text-danger" role="alert">
            Could not load your subscriptions.
          </p>
        )}
        {!query.isPending && !query.isError && subscriptions.length === 0 && (
          <div className="p-10 text-center">
            <p className="font-medium">{status ? "No subscriptions with this status" : "No subscriptions yet"}</p>
            {!status && <p className="mt-1 text-sm text-muted">Create a product with a recurring price, save a customer card, then start one.</p>}
          </div>
        )}
      </Card>
      {query.hasNextPage && (
        <div className="text-center">
          <Button variant="secondary" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>
            Load more
          </Button>
        </div>
      )}

      <RetryRules />
    </div>
  );
}
