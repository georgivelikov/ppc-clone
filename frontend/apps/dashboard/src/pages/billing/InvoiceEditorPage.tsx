import { ApiError, type Customer } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, formatMoney, Input, Label, toMinorUnits } from "@cutpay/ui";
import { useMutation } from "@tanstack/react-query";
import { ArrowLeft, Plus, Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { CustomerPicker } from "../../components/CustomerPicker";
import { useTestMode } from "../../layout/testMode";

interface LineDraft {
  key: number;
  description: string;
  unit: string;
  quantity: string;
}

/** Subtotal, tax and total of the lines typed so far; the server works the same sums out when it saves them. */
export function draftTotals(lines: { unit: number | null; quantity: number }[], taxPercent: number) {
  const subtotal = lines.reduce((sum, line) => sum + (line.unit ?? 0) * line.quantity, 0);
  const tax = subtotal > 0 ? Math.round((subtotal * taxPercent) / 100) : 0;
  return { subtotal, tax, total: subtotal + tax };
}

export function InvoiceEditorPage() {
  const navigate = useNavigate();
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const currency = merchant.default_currency;
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [lines, setLines] = useState<LineDraft[]>([{ key: 1, description: "", unit: "", quantity: "1" }]);
  const [tax, setTax] = useState("");
  const [method, setMethod] = useState<"send_invoice" | "charge_automatically">("send_invoice");
  const [days, setDays] = useState("14");
  const [note, setNote] = useState("");
  const [problems, setProblems] = useState<Record<string, string>>({});

  const parsed = lines.map((line) => ({ unit: toMinorUnits(line.unit, currency, true), quantity: Number(line.quantity) || 0 }));
  const totals = draftTotals(parsed, Number(tax) || 0);

  const create = useMutation({
    mutationFn: async (finalize: boolean) => {
      const invoice = await api.createInvoice(
        {
          customer: customer!.id,
          currency,
          lines: lines.map((line, i) => ({ description: line.description.trim(), unit_amount: parsed[i]!.unit!, quantity: parsed[i]!.quantity })),
          collection_method: method,
          days_until_due: method === "send_invoice" ? Number(days) : undefined,
          tax_rate_percent: tax.trim() ? Number(tax) : undefined,
          description: note.trim() || undefined,
          merchant_name: merchant.display_name,
        },
        livemode,
      );
      return finalize ? api.finalizeInvoice(invoice.id) : invoice;
    },
    onSuccess: (invoice) => navigate(`/invoices/${invoice.id}`),
  });

  function submit(finalize: boolean) {
    const found: Record<string, string> = {};
    if (!customer) found.customer = "Choose a customer";
    lines.forEach((line, i) => {
      if (!line.description.trim()) found[`description-${i}`] = "Describe the line";
      if (parsed[i]!.unit === null || parsed[i]!.unit === 0) found[`unit-${i}`] = "Enter an amount, like 25.00";
      if (!Number.isInteger(parsed[i]!.quantity) || parsed[i]!.quantity < 1) found[`quantity-${i}`] = "1 or more";
    });
    if (tax.trim() && (Number.isNaN(Number(tax)) || Number(tax) < 0 || Number(tax) > 100)) found.tax = "Enter a percentage from 0 to 100";
    if (method === "send_invoice" && (!Number.isInteger(Number(days)) || Number(days) < 0)) found.days = "Enter the days until it is due";
    if (totals.total <= 0) found.total = "The invoice has to come to more than zero";
    setProblems(found);
    if (Object.keys(found).length === 0) create.mutate(finalize);
  }

  const update = (key: number, patch: Partial<LineDraft>) => setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)));

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link to="/invoices" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Invoices
      </Link>
      <h1 className="text-2xl font-semibold">Create invoice</h1>
      <Card>
        <form
          noValidate
          className="space-y-5"
          aria-label="New invoice"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            submit(true);
          }}
        >
          {create.isError && <Alert>{create.error instanceof ApiError ? create.error.message : "Could not create the invoice."}</Alert>}
          <div>
            <Label htmlFor="invoice-customer">Customer</Label>
            <CustomerPicker id="invoice-customer" value={customer} onChange={setCustomer} />
            <FieldError>{problems.customer}</FieldError>
          </div>

          <div className="space-y-3">
            <p className="text-sm font-medium">Lines</p>
            {lines.map((line, i) => (
              <div key={line.key} className="grid gap-2 sm:grid-cols-[1fr_7rem_5rem_auto] sm:items-start" role="group" aria-label={`Line ${i + 1}`}>
                <div>
                  <Input aria-label={`Description ${i + 1}`} placeholder="Description" value={line.description} onChange={(e) => update(line.key, { description: e.target.value })} aria-invalid={Boolean(problems[`description-${i}`])} />
                  <FieldError>{problems[`description-${i}`]}</FieldError>
                </div>
                <div>
                  <Input aria-label={`Unit price ${i + 1}`} inputMode="decimal" placeholder="25.00" value={line.unit} onChange={(e) => update(line.key, { unit: e.target.value })} aria-invalid={Boolean(problems[`unit-${i}`])} />
                  <FieldError>{problems[`unit-${i}`]}</FieldError>
                </div>
                <div>
                  <Input aria-label={`Quantity ${i + 1}`} inputMode="numeric" value={line.quantity} onChange={(e) => update(line.key, { quantity: e.target.value })} aria-invalid={Boolean(problems[`quantity-${i}`])} />
                  <FieldError>{problems[`quantity-${i}`]}</FieldError>
                </div>
                <Button type="button" variant="secondary" size="sm" aria-label={`Remove line ${i + 1}`} disabled={lines.length === 1} onClick={() => setLines((current) => current.filter((l) => l.key !== line.key))}>
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </div>
            ))}
            <Button type="button" variant="secondary" size="sm" onClick={() => setLines((current) => [...current, { key: Math.max(...current.map((l) => l.key)) + 1, description: "", unit: "", quantity: "1" }])}>
              <Plus className="size-4" aria-hidden />
              Add line
            </Button>
            <p className="text-xs text-muted">Use a negative amount for a discount.</p>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="invoice-tax">Tax % (optional)</Label>
              <Input id="invoice-tax" inputMode="decimal" value={tax} onChange={(e) => setTax(e.target.value)} aria-invalid={Boolean(problems.tax)} />
              <FieldError>{problems.tax}</FieldError>
            </div>
            <div>
              <Label htmlFor="invoice-note">Note (optional)</Label>
              <Input id="invoice-note" value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
          </div>

          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Collection</legend>
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" name="invoice-method" checked={method === "send_invoice"} onChange={() => setMethod("send_invoice")} />
              Email the customer a link to pay
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" name="invoice-method" checked={method === "charge_automatically"} onChange={() => setMethod("charge_automatically")} />
              Charge the saved card
            </label>
            {method === "send_invoice" && (
              <div className="max-w-40">
                <Label htmlFor="invoice-days">Due in (days)</Label>
                <Input id="invoice-days" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} aria-invalid={Boolean(problems.days)} />
                <FieldError>{problems.days}</FieldError>
              </div>
            )}
          </fieldset>

          <dl className="ml-auto max-w-xs space-y-1 border-t border-border pt-3 text-sm" aria-label="Totals">
            <div className="flex justify-between">
              <dt className="text-muted">Subtotal</dt>
              <dd className="tabular-nums" data-testid="draft-subtotal">
                {formatMoney(totals.subtotal, currency)}
              </dd>
            </div>
            {totals.tax > 0 && (
              <div className="flex justify-between">
                <dt className="text-muted">Tax</dt>
                <dd className="tabular-nums">{formatMoney(totals.tax, currency)}</dd>
              </div>
            )}
            <div className="flex justify-between font-semibold">
              <dt>Total</dt>
              <dd className="tabular-nums" data-testid="draft-total">
                {formatMoney(totals.total, currency)}
              </dd>
            </div>
            <FieldError>{problems.total}</FieldError>
          </dl>

          <div className="flex flex-wrap gap-2">
            <Button type="submit" loading={create.isPending}>
              Create and send
            </Button>
            <Button type="button" variant="secondary" onClick={() => submit(false)} disabled={create.isPending}>
              Save as draft
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}
