import { ApiError, type Customer } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { CustomerPicker } from "../../components/CustomerPicker";
import { useTestMode } from "../../layout/testMode";
import { priceLabel } from "../../lib/billing";

export function NewSubscriptionPage() {
  const navigate = useNavigate();
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [params] = useSearchParams();
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [priceId, setPriceId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [trial, setTrial] = useState("");
  const [method, setMethod] = useState<"charge_automatically" | "send_invoice">("charge_automatically");
  const [tax, setTax] = useState("");
  const [problems, setProblems] = useState<Record<string, string>>({});

  const preselected = params.get("customer");
  useQuery({
    queryKey: ["customer", preselected],
    queryFn: async () => {
      const found = await api.getCustomer(preselected!);
      setCustomer((current) => current ?? found);
      return found;
    },
    enabled: Boolean(preselected),
  });
  const prices = useQuery({ queryKey: ["prices", { livemode, recurring: true }], queryFn: () => api.listPrices({ active: true, limit: 100, livemode }) });
  const products = useQuery({ queryKey: ["products", { livemode }], queryFn: () => api.listProducts({ limit: 100, livemode }) });
  const recurring = (prices.data?.data ?? []).filter((price) => price.type === "recurring");
  const productName = (id: string) => products.data?.data.find((p) => p.id === id)?.name ?? id;

  const create = useMutation({
    mutationFn: () =>
      api.createSubscription(
        {
          customer: customer!.id,
          items: [{ price: priceId, quantity: Number(quantity) }],
          trial_days: trial.trim() ? Number(trial) : undefined,
          collection_method: method,
          tax_rate_percent: tax.trim() ? Number(tax) : undefined,
          merchant_name: merchant.display_name,
        },
        livemode,
      ),
    onSuccess: (sub) => navigate(`/subscriptions/${sub.id}`),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (!customer) found.customer = "Choose a customer";
    if (!priceId) found.price = "Choose a plan";
    const qty = Number(quantity);
    if (!Number.isInteger(qty) || qty < 1) found.quantity = "Enter a whole number, 1 or more";
    if (trial.trim() && (!Number.isInteger(Number(trial)) || Number(trial) < 0 || Number(trial) > 730)) found.trial = "Enter 0 to 730 days";
    if (tax.trim() && (Number.isNaN(Number(tax)) || Number(tax) < 0 || Number(tax) > 100)) found.tax = "Enter a percentage from 0 to 100";
    setProblems(found);
    if (Object.keys(found).length === 0) create.mutate();
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <Link to="/subscriptions" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Subscriptions
      </Link>
      <h1 className="text-2xl font-semibold">New subscription</h1>
      <Card>
        <form onSubmit={submit} noValidate className="space-y-5" aria-label="New subscription">
          {create.isError && <Alert>{create.error instanceof ApiError ? create.error.message : "Could not start the subscription."}</Alert>}
          <div>
            <Label htmlFor="sub-customer">Customer</Label>
            <CustomerPicker id="sub-customer" value={customer} onChange={setCustomer} />
            <FieldError>{problems.customer}</FieldError>
            {customer && method === "charge_automatically" && !customer.default_payment_method && (
              <p className="mt-1 text-sm text-warning">This customer has no saved card yet. Save one first, or choose to send invoices.</p>
            )}
          </div>
          <div>
            <Label htmlFor="sub-price">Plan</Label>
            <select id="sub-price" value={priceId} onChange={(e) => setPriceId(e.target.value)} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm" aria-invalid={Boolean(problems.price)}>
              <option value="">Choose a plan</option>
              {recurring.map((price) => (
                <option key={price.id} value={price.id}>
                  {productName(price.product)} — {priceLabel(price)}
                  {price.trial_days > 0 ? ` (${price.trial_days}-day trial)` : ""}
                </option>
              ))}
            </select>
            <FieldError>{problems.price}</FieldError>
            {prices.isSuccess && recurring.length === 0 && (
              <p className="mt-1 text-sm text-muted">
                No recurring prices yet. <Link to="/products" className="text-brand-600 hover:underline">Create a product</Link> first.
              </p>
            )}
          </div>
          <div className="grid gap-4 sm:grid-cols-3">
            <div>
              <Label htmlFor="sub-quantity">Quantity</Label>
              <Input id="sub-quantity" inputMode="numeric" value={quantity} onChange={(e) => setQuantity(e.target.value)} aria-invalid={Boolean(problems.quantity)} />
              <FieldError>{problems.quantity}</FieldError>
            </div>
            <div>
              <Label htmlFor="sub-trial">Trial days (optional)</Label>
              <Input id="sub-trial" inputMode="numeric" value={trial} onChange={(e) => setTrial(e.target.value)} placeholder="plan default" aria-invalid={Boolean(problems.trial)} />
              <FieldError>{problems.trial}</FieldError>
            </div>
            <div>
              <Label htmlFor="sub-tax">Tax % (optional)</Label>
              <Input id="sub-tax" inputMode="decimal" value={tax} onChange={(e) => setTax(e.target.value)} aria-invalid={Boolean(problems.tax)} />
              <FieldError>{problems.tax}</FieldError>
            </div>
          </div>
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Collection</legend>
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" name="collection" checked={method === "charge_automatically"} onChange={() => setMethod("charge_automatically")} />
              Charge the saved card automatically
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" name="collection" checked={method === "send_invoice"} onChange={() => setMethod("send_invoice")} />
              Email an invoice each period for the customer to pay
            </label>
          </fieldset>
          <Button type="submit" loading={create.isPending}>
            Start subscription
          </Button>
        </form>
      </Card>
    </div>
  );
}
