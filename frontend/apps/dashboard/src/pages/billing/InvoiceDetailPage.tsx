import { ApiError, type Invoice } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Download, ExternalLink } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api } from "../../api";
import { InvoiceStatusBadge } from "../../components/BillingBadges";
import { saveBlob, useBlobUrl } from "../../lib/blob";

const REASON: Record<Invoice["billing_reason"], string> = {
  manual: "One-off invoice",
  subscription_create: "First invoice of a subscription",
  subscription_cycle: "Renewal of a subscription",
  subscription_update: "Plan change",
};

function Pdf({ invoice }: { invoice: Invoice }) {
  const { url, error } = useBlobUrl(() => api.invoicePdf(invoice.id), [invoice.id, invoice.status]);
  return (
    <Card aria-label="PDF preview">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">PDF</h2>
        <Button size="sm" variant="secondary" onClick={async () => saveBlob(await api.invoicePdf(invoice.id), `${invoice.number ?? invoice.id}.pdf`)}>
          <Download className="size-4" aria-hidden />
          Download
        </Button>
      </div>
      <div className="mt-3 h-[28rem] overflow-hidden rounded-lg border border-border bg-canvas">
        {url ? <iframe title="Invoice PDF" src={url} className="size-full" /> : <p className="p-4 text-sm text-muted">{error ? "Could not draw the PDF." : "Drawing…"}</p>}
      </div>
    </Card>
  );
}

export function InvoiceDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [chargeProblem, setChargeProblem] = useState<string | null>(null);
  const query = useQuery({ queryKey: ["invoice", id], queryFn: () => api.getInvoice(id) });
  const invoice = query.data;
  const emails = useQuery({
    queryKey: ["billing-notifications", id],
    queryFn: () => api.billingNotifications(id, invoice?.livemode),
    enabled: Boolean(invoice),
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ["invoice", id] });
    await queryClient.invalidateQueries({ queryKey: ["invoices"] });
    await queryClient.invalidateQueries({ queryKey: ["billing-notifications", id] });
  };
  const finalize = useMutation({ mutationFn: () => api.finalizeInvoice(id), onSuccess: refresh });
  const voidIt = useMutation({ mutationFn: () => api.voidInvoice(id), onSuccess: refresh });
  const remove = useMutation({
    mutationFn: () => api.deleteInvoice(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["invoices"] });
      navigate("/invoices");
    },
  });
  const pay = useMutation({
    mutationFn: () => api.payInvoice(id),
    onSuccess: async (result) => {
      setChargeProblem(
        result.paid
          ? null
          : result.failure_code === "authentication_required"
            ? "The bank needs the customer to confirm this payment. Send them the link to pay instead."
            : (result.failure_message ?? "The card was refused."),
      );
      await refresh();
    },
  });

  if (query.isPending) return <p className="text-sm text-muted">Loading invoice…</p>;
  if (query.isError || !invoice) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Link to="/invoices" className="text-sm text-brand-600 hover:underline">
          ← Invoices
        </Link>
        <Alert>{query.error instanceof ApiError && query.error.status === 404 ? "Invoice not found." : "Could not load the invoice."}</Alert>
      </div>
    );
  }

  const anyError = finalize.isError || voidIt.isError || remove.isError || pay.isError;
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/invoices" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Invoices
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-3 text-2xl font-semibold">
            <span data-testid="invoice-number">{invoice.number ?? "Draft invoice"}</span>
            <span data-testid="invoice-status">
              <InvoiceStatusBadge status={invoice.status} />
            </span>
          </h1>
          <p className="mt-1 text-sm text-muted">
            {REASON[invoice.billing_reason]} ·{" "}
            <Link to={`/customers/${invoice.customer}`} className="text-brand-700 hover:underline">
              {invoice.customer_name ?? invoice.customer_email ?? invoice.customer}
            </Link>
            {invoice.subscription && (
              <>
                {" "}
                ·{" "}
                <Link to={`/subscriptions/${invoice.subscription}`} className="text-brand-700 hover:underline">
                  {invoice.subscription}
                </Link>
              </>
            )}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {invoice.status === "draft" && (
            <>
              <Button onClick={() => finalize.mutate()} loading={finalize.isPending}>
                Finalize and send
              </Button>
              <Button variant="secondary" onClick={() => remove.mutate()} loading={remove.isPending}>
                Delete draft
              </Button>
            </>
          )}
          {invoice.status === "open" && (
            <>
              <Button onClick={() => pay.mutate()} loading={pay.isPending}>
                Charge saved card
              </Button>
              {invoice.hosted_invoice_url && (
                <a href={invoice.hosted_invoice_url} target="_blank" rel="noreferrer">
                  <Button variant="secondary">
                    <ExternalLink className="size-4" aria-hidden />
                    Customer page
                  </Button>
                </a>
              )}
              <Button variant="secondary" onClick={() => voidIt.mutate()} loading={voidIt.isPending}>
                Void
              </Button>
            </>
          )}
        </div>
      </div>
      {anyError && <Alert>That did not work. Please try again.</Alert>}
      {chargeProblem && <Alert>{chargeProblem}</Alert>}
      {invoice.status === "open" && invoice.failure_code && !chargeProblem && (
        <Alert role="status">
          The last payment failed ({invoice.failure_code.replaceAll("_", " ")}).
          {invoice.next_payment_attempt ? ` It will be tried again on ${new Date(invoice.next_payment_attempt).toLocaleDateString()}.` : ""}
        </Alert>
      )}
      {invoice.status === "uncollectible" && <Alert role="status">Every retry failed, so this invoice is written off as uncollectible. The customer can still pay it from their page.</Alert>}

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Description</th>
              <th className="px-4 py-3 text-right font-medium">Qty</th>
              <th className="px-4 py-3 text-right font-medium">Unit price</th>
              <th className="px-4 py-3 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border" data-testid="invoice-lines">
            {invoice.lines.map((line) => (
              <tr key={line.id}>
                <td className="px-4 py-3">
                  {line.description}
                  {line.proration && <span className="ml-2 text-xs text-muted">proration</span>}
                  {line.period_start && line.period_end && (
                    <p className="text-xs text-muted">
                      {new Date(line.period_start).toLocaleDateString()} – {new Date(line.period_end).toLocaleDateString()}
                    </p>
                  )}
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{line.quantity}</td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(line.unit_amount, invoice.currency)}</td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(line.amount, invoice.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <dl className="ml-auto max-w-xs space-y-1 p-4 text-sm">
          <div className="flex justify-between">
            <dt className="text-muted">Subtotal</dt>
            <dd className="tabular-nums">{formatMoney(invoice.subtotal, invoice.currency)}</dd>
          </div>
          {invoice.tax > 0 && (
            <div className="flex justify-between">
              <dt className="text-muted">Tax</dt>
              <dd className="tabular-nums">{formatMoney(invoice.tax, invoice.currency)}</dd>
            </div>
          )}
          <div className="flex justify-between font-semibold">
            <dt>Total</dt>
            <dd className="tabular-nums" data-testid="invoice-total">
              {formatMoney(invoice.total, invoice.currency)}
            </dd>
          </div>
          {invoice.amount_paid > 0 && (
            <div className="flex justify-between">
              <dt className="text-muted">Paid</dt>
              <dd className="tabular-nums" data-testid="invoice-paid">
                {formatMoney(invoice.amount_paid, invoice.currency)}
              </dd>
            </div>
          )}
        </dl>
      </Card>

      {invoice.attempts && invoice.attempts.length > 0 && (
        <Card aria-label="Payment attempts">
          <h2 className="font-semibold">Retries</h2>
          <ul className="mt-2 divide-y divide-border text-sm" data-testid="attempts">
            {invoice.attempts.map((attempt) => (
              <li key={attempt.attempt_no} className="flex justify-between gap-4 py-2">
                <span>Retry {attempt.attempt_no}</span>
                <span className="text-muted">
                  {attempt.executed_at
                    ? `${attempt.result ?? "done"} · ${new Date(attempt.executed_at).toLocaleDateString()}`
                    : `scheduled for ${new Date(attempt.scheduled_at).toLocaleDateString()}`}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card aria-label="Emails sent">
        <h2 className="font-semibold">Emails to the customer</h2>
        {(emails.data?.data ?? []).length === 0 ? (
          <p className="mt-2 text-sm text-muted">Nothing sent yet.</p>
        ) : (
          <ul className="mt-2 divide-y divide-border">
            {emails.data!.data.map((email) => (
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

      {invoice.number && <Pdf invoice={invoice} />}
    </div>
  );
}
