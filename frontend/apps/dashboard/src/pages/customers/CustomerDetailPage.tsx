import { ApiError, type Customer, type PaymentMethod } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, FieldError, formatMoney, Input, Label, toMinorUnits } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Copy, CreditCard } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api } from "../../api";
import { Dialog } from "../../components/Dialog";
import { InvoiceStatusBadge, SubscriptionStatusBadge } from "../../components/BillingBadges";
import { StatusBadge } from "../../components/StatusBadge";
import { useSession } from "../../auth/AuthProvider";
import { useTestMode } from "../../layout/testMode";
import { intervalLabel } from "../../lib/billing";

function describeCard(method: PaymentMethod): string {
  const brand = method.card.brand ? method.card.brand.charAt(0).toUpperCase() + method.card.brand.slice(1) : "Card";
  return `${brand} •••• ${method.card.last4 ?? "????"}`;
}

/** Creates a hosted page where the customer enters a card, and waits for them to do it. */
function AddCardDialog({ customer, livemode, onClose }: { customer: Customer; livemode: boolean; onClose: () => void }) {
  const { merchant } = useSession();
  const queryClient = useQueryClient();
  const [copied, setCopied] = useState(false);
  const create = useMutation({
    mutationFn: () => api.createSetupIntent({ customer: customer.id, merchant_name: merchant.display_name }, livemode),
  });
  const setupId = create.data?.id;
  // The customer fills in the card on another device, so keep checking until the page reports the result.
  const progress = useQuery({
    queryKey: ["setup-intent", setupId],
    queryFn: async () => {
      const setup = await api.getSetupIntent(setupId!);
      if (setup.status === "succeeded") {
        await queryClient.invalidateQueries({ queryKey: ["payment-methods", customer.id] });
        await queryClient.invalidateQueries({ queryKey: ["customer", customer.id] });
      }
      return setup;
    },
    enabled: Boolean(setupId),
    refetchInterval: (query) => (query.state.data && ["succeeded", "failed", "canceled"].includes(query.state.data.status) ? false : 2000),
  });
  const setup = progress.data ?? create.data;

  async function copy() {
    if (!setup) return;
    await navigator.clipboard?.writeText(setup.url).catch(() => undefined);
    setCopied(true);
  }

  return (
    <Dialog title="Add a card" onClose={onClose}>
      {!setup ? (
        <div className="space-y-4">
          <p className="text-sm text-muted">
            Create a page where {customer.name ?? customer.email ?? "the customer"} enters their card. They are not charged, and
            the card is kept for future payments.
          </p>
          {create.isError && <Alert>{create.error instanceof ApiError ? create.error.message : "Could not start."}</Alert>}
          <Button className="w-full" onClick={() => create.mutate()} loading={create.isPending}>
            Create card page
          </Button>
        </div>
      ) : setup.status === "succeeded" ? (
        <div className="space-y-4">
          <p role="status" className="font-medium text-success">
            Card saved.
          </p>
          <Button className="w-full" onClick={onClose}>
            Done
          </Button>
        </div>
      ) : setup.status === "failed" ? (
        <Alert>{setup.failure_message ?? "The card could not be saved."}</Alert>
      ) : (
        <div className="space-y-4">
          <p className="text-sm text-muted">Send this link to the customer. This window updates when they are done.</p>
          <div className="flex gap-2">
            <Input readOnly value={setup.url} aria-label="Card page link" data-testid="setup-url" />
            <Button variant="secondary" onClick={copy} aria-label="Copy link">
              <Copy className="size-4" aria-hidden />
              {copied ? "Copied" : "Copy"}
            </Button>
          </div>
          <a href={setup.url} target="_blank" rel="noreferrer" className="inline-block text-sm font-medium text-brand-600 hover:underline">
            Open the card page
          </a>
          <p className="text-sm text-muted" role="status">
            Waiting for the customer…
          </p>
        </div>
      )}
    </Dialog>
  );
}

function ChargeDialog({ customer, livemode, onClose }: { customer: Customer; livemode: boolean; onClose: () => void }) {
  const { merchant } = useSession();
  const queryClient = useQueryClient();
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const charge = useMutation({
    mutationFn: (minor: number) =>
      api.createPayment(
        {
          amount: minor,
          currency: merchant.default_currency,
          customer: customer.id,
          off_session: true,
          description: description.trim() || undefined,
          merchant_name: merchant.display_name,
        },
        livemode,
      ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["customer-payments", customer.id] });
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const minor = toMinorUnits(amount, merchant.default_currency);
    if (minor === null || minor < 1) {
      setFieldError("Enter an amount, like 12.50");
      return;
    }
    setFieldError(null);
    charge.mutate(minor);
  }

  const result = charge.data;
  return (
    <Dialog title="Charge saved card" onClose={onClose}>
      {result ? (
        <div className="space-y-4" role="status">
          {result.status === "succeeded" ? (
            <p className="font-medium text-success">Charged {formatMoney(result.amount, result.currency)}.</p>
          ) : (
            <Alert>
              {result.failure_code === "authentication_required"
                ? "The bank needs the customer to authenticate this payment, so it cannot be charged without them."
                : (result.failure_message ?? "The charge failed.")}
            </Alert>
          )}
          <Button className="w-full" onClick={onClose}>
            Close
          </Button>
        </div>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-4">
          <p className="text-sm text-muted">Charges the default saved card without the customer being there.</p>
          {charge.isError && <Alert>{charge.error instanceof ApiError ? charge.error.message : "Could not charge."}</Alert>}
          <div>
            <Label htmlFor="charge-amount">Amount ({merchant.default_currency})</Label>
            <Input id="charge-amount" inputMode="decimal" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} aria-invalid={!!fieldError} />
            <FieldError>{fieldError}</FieldError>
          </div>
          <div>
            <Label htmlFor="charge-description">Description (optional)</Label>
            <Input id="charge-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <Button type="submit" className="w-full" loading={charge.isPending}>
            Charge
          </Button>
        </form>
      )}
    </Dialog>
  );
}

export function CustomerDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [adding, setAdding] = useState(false);
  const [charging, setCharging] = useState(false);

  const customer = useQuery({ queryKey: ["customer", id], queryFn: () => api.getCustomer(id) });
  const methods = useQuery({ queryKey: ["payment-methods", id], queryFn: () => api.listPaymentMethods(id) });
  const payments = useQuery({
    queryKey: ["customer-payments", id],
    queryFn: () => api.listPayments({ customer: id, limit: 10, livemode: customer.data?.livemode }),
    enabled: customer.isSuccess,
  });
  const subscriptions = useQuery({
    queryKey: ["customer-subscriptions", id],
    queryFn: () => api.listSubscriptions({ customer: id, limit: 10, livemode: customer.data?.livemode }),
    enabled: customer.isSuccess,
  });
  const invoices = useQuery({
    queryKey: ["customer-invoices", id],
    queryFn: () => api.listInvoices({ customer: id, limit: 10, livemode: customer.data?.livemode }),
    enabled: customer.isSuccess,
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ["payment-methods", id] });
    await queryClient.invalidateQueries({ queryKey: ["customer", id] });
  };
  const makeDefault = useMutation({
    mutationFn: (paymentMethodId: string) => api.updateCustomer(id, { default_payment_method: paymentMethodId }),
    onSuccess: refresh,
  });
  const remove = useMutation({ mutationFn: (paymentMethodId: string) => api.detachPaymentMethod(id, paymentMethodId), onSuccess: refresh });
  const deleteCustomer = useMutation({
    mutationFn: () => api.deleteCustomer(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["customers"] });
      navigate("/customers");
    },
  });

  if (customer.isPending) return <p className="text-sm text-muted">Loading customer…</p>;
  if (customer.isError || !customer.data) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Link to="/customers" className="text-sm text-brand-600 hover:underline">
          ← Customers
        </Link>
        <Alert>{customer.error instanceof ApiError && customer.error.status === 404 ? "Customer not found." : "Could not load the customer."}</Alert>
      </div>
    );
  }
  const c = customer.data;
  const cards = methods.data?.data ?? [];

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/customers" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Customers
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold" data-testid="customer-name">
            {c.name ?? c.email ?? c.id}
          </h1>
          <p className="mt-1 text-sm text-muted">
            {c.email ?? "No email"} · {c.id}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setCharging(true)} disabled={cards.length === 0}>
            Charge saved card
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              if (window.confirm("Delete this customer and their saved cards?")) deleteCustomer.mutate();
            }}
            loading={deleteCustomer.isPending}
          >
            Delete
          </Button>
        </div>
      </div>

      <Card>
        <div className="flex items-center justify-between">
          <h2 className="font-semibold">Saved cards</h2>
          <Button size="sm" onClick={() => setAdding(true)}>
            <CreditCard className="size-4" aria-hidden />
            Add a card
          </Button>
        </div>
        {(makeDefault.isError || remove.isError) && <Alert className="mt-3">Could not change the card.</Alert>}
        {methods.isPending ? (
          <p className="mt-3 text-sm text-muted">Loading…</p>
        ) : cards.length === 0 ? (
          <p className="mt-3 text-sm text-muted">No saved cards. Add one to charge this customer later or start a subscription.</p>
        ) : (
          <ul className="mt-3 divide-y divide-border" aria-label="Saved cards">
            {cards.map((method) => (
              <li key={method.id} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm">
                <span>
                  <span className="font-medium" data-testid="saved-card">
                    {describeCard(method)}
                  </span>
                  <span className="ml-2 text-muted">
                    expires {method.card.exp_month}/{method.card.exp_year}
                  </span>
                  {method.is_default && <Badge className="ml-2">Default</Badge>}
                </span>
                <span className="flex gap-2">
                  {!method.is_default && (
                    <Button size="sm" variant="secondary" onClick={() => makeDefault.mutate(method.id)} loading={makeDefault.isPending}>
                      Make default
                    </Button>
                  )}
                  <Button size="sm" variant="secondary" onClick={() => remove.mutate(method.id)} loading={remove.isPending}>
                    Remove
                  </Button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card aria-label="Subscriptions">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold">Subscriptions</h2>
          <Link to={`/subscriptions/new?customer=${c.id}`}>
            <Button size="sm" variant="secondary">
              Start a subscription
            </Button>
          </Link>
        </div>
        {(subscriptions.data?.data ?? []).length === 0 ? (
          <p className="mt-3 text-sm text-muted">No subscriptions.</p>
        ) : (
          <ul className="mt-3 divide-y divide-border">
            {subscriptions.data!.data.map((sub) => (
              <li key={sub.id} className="flex flex-wrap items-center justify-between gap-3 py-2 text-sm">
                <Link to={`/subscriptions/${sub.id}`} className="font-medium text-brand-700 hover:underline">
                  {sub.items.map((item) => item.product_name).join(", ")} ·{" "}
                  {formatMoney(sub.items.reduce((sum, item) => sum + item.amount, 0), sub.currency)} {intervalLabel(sub.interval, sub.interval_count)}
                </Link>
                <SubscriptionStatusBadge status={sub.status} />
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card aria-label="Invoices">
        <h2 className="font-semibold">Invoices</h2>
        {(invoices.data?.data ?? []).length === 0 ? (
          <p className="mt-3 text-sm text-muted">No invoices.</p>
        ) : (
          <ul className="mt-3 divide-y divide-border">
            {invoices.data!.data.map((invoice) => (
              <li key={invoice.id} className="flex flex-wrap items-center justify-between gap-3 py-2 text-sm">
                <Link to={`/invoices/${invoice.id}`} className="font-medium text-brand-700 hover:underline">
                  {invoice.number ?? "Draft"} · {formatMoney(invoice.total, invoice.currency)}
                </Link>
                <InvoiceStatusBadge status={invoice.status} />
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card className="overflow-x-auto p-0">
        <h2 className="px-4 pt-4 font-semibold">Payments</h2>
        <table className="mt-2 w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Amount</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Description</th>
              <th className="px-4 py-3 font-medium">Date</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {(payments.data?.data ?? []).map((payment) => (
              <tr key={payment.id} className="hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/payments/${payment.id}`} className="font-medium text-brand-700 hover:underline">
                    {formatMoney(payment.amount, payment.currency)}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={payment.status} />
                </td>
                <td className="px-4 py-3 text-muted">{payment.description ?? "—"}</td>
                <td className="px-4 py-3 text-muted">{new Date(payment.created_at).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {payments.isSuccess && payments.data.data.length === 0 && <p className="p-4 text-sm text-muted">No payments yet.</p>}
      </Card>

      {adding && <AddCardDialog customer={c} livemode={livemode} onClose={() => setAdding(false)} />}
      {charging && <ChargeDialog customer={c} livemode={livemode} onClose={() => setCharging(false)} />}
    </div>
  );
}
