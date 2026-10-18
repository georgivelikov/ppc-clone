import { ApiError, type Subscription } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, formatMoney, Input, Label } from "@cutpay/ui";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api";
import { InvoiceStatusBadge, SubscriptionStatusBadge } from "../../components/BillingBadges";
import { Dialog } from "../../components/Dialog";
import { useTestMode } from "../../layout/testMode";
import { intervalLabel, priceLabel } from "../../lib/billing";

function ChangePlanDialog({ subscription, livemode, onClose }: { subscription: Subscription; livemode: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const current = subscription.items[0]!;
  const [priceId, setPriceId] = useState(current.price);
  const [quantity, setQuantity] = useState(String(current.quantity));
  const [prorate, setProrate] = useState(true);
  const [now, setNow] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const prices = useQuery({ queryKey: ["prices", { livemode, active: true }], queryFn: () => api.listPrices({ active: true, limit: 100, livemode }) });
  const products = useQuery({ queryKey: ["products", { livemode }], queryFn: () => api.listProducts({ limit: 100, livemode }) });
  const options = (prices.data?.data ?? []).filter(
    (price) => price.type === "recurring" && price.currency === subscription.currency && price.interval === subscription.interval && price.interval_count === subscription.interval_count,
  );
  const qty = Number(quantity);
  const validQuantity = Number.isInteger(qty) && qty >= 1;
  const changed = priceId !== current.price || (validQuantity && qty !== current.quantity);

  const preview = useQuery({
    queryKey: ["proration-preview", subscription.id, priceId, qty],
    queryFn: () => api.previewSubscriptionChange(subscription.id, [{ price: priceId, quantity: qty }]),
    enabled: changed && validQuantity,
  });

  const save = useMutation({
    mutationFn: () =>
      api.updateSubscription(subscription.id, {
        items: [{ price: priceId, quantity: qty }],
        proration_behavior: prorate ? "create_prorations" : "none",
        bill_immediately: prorate && now,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["subscription", subscription.id] });
      await queryClient.invalidateQueries({ queryKey: ["upcoming-invoice", subscription.id] });
      await queryClient.invalidateQueries({ queryKey: ["subscription-invoices", subscription.id] });
      onClose();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!validQuantity) {
      setProblem("Enter a whole number, 1 or more");
    } else if (!changed) {
      setProblem("Choose a different plan or quantity");
    } else {
      setProblem(null);
      save.mutate();
    }
  }

  const net = preview.data?.net ?? 0;
  return (
    <Dialog title="Change plan" onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {save.isError && <Alert>{save.error instanceof ApiError ? save.error.message : "Could not change the plan."}</Alert>}
        <div>
          <Label htmlFor="change-price">Plan</Label>
          <select id="change-price" value={priceId} onChange={(e) => setPriceId(e.target.value)} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm">
            {options.map((price) => (
              <option key={price.id} value={price.id}>
                {products.data?.data.find((p) => p.id === price.product)?.name ?? price.product} — {priceLabel(price)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <Label htmlFor="change-quantity">Quantity</Label>
          <Input id="change-quantity" inputMode="numeric" value={quantity} onChange={(e) => setQuantity(e.target.value)} aria-invalid={Boolean(problem)} />
          <FieldError>{problem}</FieldError>
        </div>
        {changed && validQuantity && (
          <div className="rounded-lg bg-canvas p-3 text-sm" aria-live="polite" data-testid="proration-preview">
            {preview.isPending && <p className="text-muted">Working out the difference…</p>}
            {preview.isSuccess && preview.data.lines.length === 0 && <p className="text-muted">Nothing to charge or credit: the trial or the end of the period is here.</p>}
            {preview.isSuccess && preview.data.lines.length > 0 && (
              <>
                <ul className="space-y-1">
                  {preview.data.lines.map((line) => (
                    <li key={line.description} className="flex justify-between gap-4">
                      <span>{line.description}</span>
                      <span className="tabular-nums">{formatMoney(line.amount, preview.data.currency)}</span>
                    </li>
                  ))}
                </ul>
                <p className="mt-2 flex justify-between border-t border-border pt-2 font-medium">
                  <span>{net >= 0 ? "To pay for the rest of the period" : "Credit for the rest of the period"}</span>
                  <span className="tabular-nums" data-testid="proration-net">
                    {formatMoney(Math.abs(net), preview.data.currency)}
                  </span>
                </p>
              </>
            )}
          </div>
        )}
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" checked={prorate} onChange={(e) => setProrate(e.target.checked)} />
          <span>Prorate: credit the unused time of the old plan and charge the rest of the period at the new one</span>
        </label>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" checked={now} disabled={!prorate} onChange={(e) => setNow(e.target.checked)} />
          <span>Charge the difference now instead of with the next invoice</span>
        </label>
        <Button type="submit" className="w-full" loading={save.isPending}>
          Change plan
        </Button>
      </form>
    </Dialog>
  );
}

export function SubscriptionDetailPage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [changing, setChanging] = useState(false);
  const [canceling, setCanceling] = useState(false);

  const query = useQuery({ queryKey: ["subscription", id], queryFn: () => api.getSubscription(id) });
  const sub = query.data;
  const ended = sub?.status === "canceled";
  const upcoming = useQuery({ queryKey: ["upcoming-invoice", id], queryFn: () => api.upcomingInvoice(id), enabled: Boolean(sub) && !ended });
  const invoices = useQuery({
    queryKey: ["subscription-invoices", id],
    queryFn: () => api.listInvoices({ subscription: id, limit: 12, livemode: sub?.livemode }),
    enabled: Boolean(sub),
    refetchInterval: 5000,
  });
  const emails = useQueries({
    queries: [id, ...(invoices.data?.data.map((invoice) => invoice.id) ?? [])].map((related) => ({
      queryKey: ["billing-notifications", related],
      queryFn: () => api.billingNotifications(related, sub?.livemode),
      enabled: Boolean(sub),
    })),
  });
  const sent = emails
    .flatMap((q) => q.data?.data ?? [])
    .sort((a, b) => b.created_at.localeCompare(a.created_at));

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ["subscription", id] });
    await queryClient.invalidateQueries({ queryKey: ["upcoming-invoice", id] });
    await queryClient.invalidateQueries({ queryKey: ["subscription-invoices", id] });
    await queryClient.invalidateQueries({ queryKey: ["subscriptions"] });
  };
  const pause = useMutation({ mutationFn: () => api.pauseSubscription(id), onSuccess: refresh });
  const resume = useMutation({ mutationFn: () => api.resumeSubscription(id), onSuccess: refresh });
  const cancel = useMutation({
    mutationFn: (atPeriodEnd: boolean) => api.cancelSubscription(id, atPeriodEnd),
    onSuccess: async () => {
      await refresh();
      setCanceling(false);
    },
  });
  const undoCancel = useMutation({ mutationFn: () => api.updateSubscription(id, { cancel_at_period_end: false }), onSuccess: refresh });

  if (query.isPending) return <p className="text-sm text-muted">Loading subscription…</p>;
  if (query.isError || !sub) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Link to="/subscriptions" className="text-sm text-brand-600 hover:underline">
          ← Subscriptions
        </Link>
        <Alert>{query.error instanceof ApiError && query.error.status === 404 ? "Subscription not found." : "Could not load the subscription."}</Alert>
      </div>
    );
  }
  const total = sub.items.reduce((sum, item) => sum + item.amount, 0);

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/subscriptions" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Subscriptions
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-3 text-2xl font-semibold">
            <span data-testid="subscription-total">
              {formatMoney(total, sub.currency)} {intervalLabel(sub.interval, sub.interval_count)}
            </span>
            <span data-testid="subscription-status">
              <SubscriptionStatusBadge status={sub.status} />
            </span>
          </h1>
          <p className="mt-1 text-sm text-muted">
            <Link to={`/customers/${sub.customer}`} className="text-brand-700 hover:underline">
              {sub.customer_name ?? sub.customer_email ?? sub.customer}
            </Link>{" "}
            · {sub.id}
          </p>
        </div>
        {!ended && (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => setChanging(true)}>
              Change plan
            </Button>
            {sub.collection_paused ? (
              <Button variant="secondary" onClick={() => resume.mutate()} loading={resume.isPending}>
                Resume
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => pause.mutate()} loading={pause.isPending}>
                Pause
              </Button>
            )}
            <Button variant="secondary" onClick={() => setCanceling(true)}>
              Cancel
            </Button>
          </div>
        )}
      </div>
      {(pause.isError || resume.isError || undoCancel.isError) && <Alert>That did not work. Please try again.</Alert>}
      {sub.cancel_at_period_end && !ended && (
        <Alert tone="info" role="status">
          This subscription ends on {new Date(sub.current_period_end).toLocaleDateString()}.{" "}
          <button className="font-medium underline" onClick={() => undoCancel.mutate()}>
            Keep it
          </button>
        </Alert>
      )}
      {sub.collection_paused && !ended && <Alert tone="info" role="status">Collection is paused: no new invoices are created.</Alert>}
      {sub.status === "past_due" && <Alert role="status">The last payment failed. It will be tried again, see the invoices below.</Alert>}

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="font-semibold">Plan</h2>
          <ul className="mt-2 divide-y divide-border" aria-label="Items">
            {sub.items.map((item) => (
              <li key={item.id} className="flex justify-between gap-4 py-2 text-sm">
                <span>
                  {item.product_name}
                  {item.nickname ? ` (${item.nickname})` : ""} <span className="text-muted">× {item.quantity}</span>
                </span>
                <span className="tabular-nums">{formatMoney(item.amount, sub.currency)}</span>
              </li>
            ))}
          </ul>
          <dl className="mt-2 divide-y divide-border border-t border-border text-sm">
            <div className="flex justify-between py-2">
              <dt className="text-muted">Current period</dt>
              <dd>
                {new Date(sub.current_period_start).toLocaleDateString()} – {new Date(sub.current_period_end).toLocaleDateString()}
              </dd>
            </div>
            {sub.trial_end && (
              <div className="flex justify-between py-2">
                <dt className="text-muted">Trial ends</dt>
                <dd data-testid="trial-end">{new Date(sub.trial_end).toLocaleDateString()}</dd>
              </div>
            )}
            <div className="flex justify-between py-2">
              <dt className="text-muted">Collection</dt>
              <dd>{sub.collection_method === "charge_automatically" ? "Saved card" : "Invoice by email"}</dd>
            </div>
            {sub.tax_rate_percent > 0 && (
              <div className="flex justify-between py-2">
                <dt className="text-muted">Tax</dt>
                <dd>{sub.tax_rate_percent}%</dd>
              </div>
            )}
          </dl>
        </Card>

        <Card aria-label="Upcoming invoice">
          <h2 className="font-semibold">Upcoming invoice</h2>
          {ended && <p className="mt-2 text-sm text-muted">This subscription has ended.</p>}
          {upcoming.isSuccess && (
            <>
              <p className="mt-1 text-xs text-muted">
                {new Date(upcoming.data.period_start).toLocaleDateString()} – {new Date(upcoming.data.period_end).toLocaleDateString()}
              </p>
              <ul className="mt-2 space-y-1 text-sm">
                {upcoming.data.lines.map((line) => (
                  <li key={line.id} className="flex justify-between gap-4">
                    <span>{line.description}</span>
                    <span className="tabular-nums">{formatMoney(line.amount, upcoming.data.currency)}</span>
                  </li>
                ))}
              </ul>
              {upcoming.data.credit_applied > 0 && (
                <p className="mt-2 flex justify-between text-sm text-muted">
                  <span>Credit used</span>
                  <span className="tabular-nums">−{formatMoney(upcoming.data.credit_applied, upcoming.data.currency)}</span>
                </p>
              )}
              <p className="mt-2 flex justify-between border-t border-border pt-2 font-medium">
                <span>Total</span>
                <span className="tabular-nums" data-testid="upcoming-total">
                  {formatMoney(upcoming.data.total, upcoming.data.currency)}
                </span>
              </p>
            </>
          )}
        </Card>
      </div>

      <Card className="overflow-x-auto p-0">
        <h2 className="px-4 pt-4 font-semibold">Invoices</h2>
        <table className="mt-2 w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Invoice</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 text-right font-medium">Total</th>
              <th className="px-4 py-3 font-medium">Period</th>
              <th className="px-4 py-3 font-medium">Tries</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border" data-testid="subscription-invoices">
            {(invoices.data?.data ?? []).map((invoice) => (
              <tr key={invoice.id} className="hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/invoices/${invoice.id}`} className="font-medium text-brand-700 hover:underline">
                    {invoice.number ?? invoice.id}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <InvoiceStatusBadge status={invoice.status} />
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(invoice.total, invoice.currency)}</td>
                <td className="px-4 py-3 text-muted">
                  {invoice.period_start ? new Date(invoice.period_start).toLocaleDateString() : "—"}
                </td>
                <td className="px-4 py-3 text-muted">
                  {invoice.attempt_count}
                  {invoice.status === "open" && invoice.failure_code ? ` · ${invoice.failure_code.replaceAll("_", " ")}` : ""}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {invoices.isSuccess && invoices.data.data.length === 0 && <p className="p-4 text-sm text-muted">No invoices yet. The first one is created when the trial ends.</p>}
      </Card>

      <Card aria-label="Emails sent">
        <h2 className="font-semibold">Emails to the customer</h2>
        {sent.length === 0 ? (
          <p className="mt-2 text-sm text-muted">Nothing sent yet.</p>
        ) : (
          <ul className="mt-2 divide-y divide-border" data-testid="emails">
            {sent.map((email) => (
              <li key={email.id} className="py-3 text-sm">
                <p className="font-medium">{email.subject}</p>
                <p className="text-muted">{email.body}</p>
                <p className="mt-1 text-xs text-muted">
                  To {email.recipient ?? "—"} · {new Date(email.created_at).toLocaleString()}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {changing && <ChangePlanDialog subscription={sub} livemode={livemode} onClose={() => setChanging(false)} />}
      {canceling && (
        <Dialog title="Cancel subscription" onClose={() => setCanceling(false)}>
          <div className="space-y-3">
            <p className="text-sm text-muted">Nothing is refunded for the period that is already paid.</p>
            {cancel.isError && <Alert>Could not cancel. Please try again.</Alert>}
            <Button className="w-full" variant="secondary" onClick={() => cancel.mutate(true)} loading={cancel.isPending}>
              Cancel at the end of the period
            </Button>
            <Button className="w-full" onClick={() => cancel.mutate(false)} loading={cancel.isPending}>
              Cancel now
            </Button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
