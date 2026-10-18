import { Alert, Badge, Button, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { useParams, useSearchParams } from "react-router";
import { CheckoutApiError, getInvoice, startInvoicePayment } from "./api";
import { Shell } from "./Shell";

const STATUS = {
  open: { label: "Awaiting payment", tone: "warning" },
  paid: { label: "Paid", tone: "success" },
  void: { label: "Void", tone: "neutral" },
  uncollectible: { label: "Overdue", tone: "danger" },
} as const;

/** The page a customer opens from the link in an invoice email: what they owe, and a button to pay it. */
export function InvoicePage() {
  const { token = "" } = useParams();
  const [params] = useSearchParams();
  const returned = params.get("returned") === "1";
  const query = useQuery({
    queryKey: ["invoice", token],
    queryFn: () => getInvoice(token),
    // The payment is confirmed a moment after the customer comes back, so look again for a little while.
    refetchInterval: (state) => (returned && state.state.data?.status === "open" && state.state.dataUpdateCount < 15 ? 2000 : false),
  });
  const pay = useMutation({
    mutationFn: () => startInvoicePayment(token),
    onSuccess: ({ url }) => window.location.assign(url),
  });

  if (query.isPending) {
    return (
      <Shell>
        <p className="text-sm text-muted" role="status">
          Loading invoice…
        </p>
      </Shell>
    );
  }
  if (query.isError || !query.data) {
    const notFound = query.error instanceof CheckoutApiError && query.error.status === 404;
    return (
      <Shell>
        <h1 className="text-xl font-semibold">{notFound ? "Invoice not found" : "Something went wrong"}</h1>
        <Alert className="mt-4">{notFound ? "This invoice link is not valid." : "We could not load this invoice. Please try again."}</Alert>
      </Shell>
    );
  }

  const invoice = query.data;
  const status = STATUS[invoice.status];
  const confirming = returned && invoice.status === "open";
  return (
    <Shell>
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm text-muted">{invoice.merchant_name ?? "Invoice"}</p>
          <h1 className="text-xl font-semibold" data-testid="invoice-number">
            {invoice.number}
          </h1>
        </div>
        <Badge tone={status.tone} data-testid="invoice-status">
          {status.label}
        </Badge>
      </div>
      {invoice.customer_name && <p className="mt-2 text-sm text-muted">Billed to {invoice.customer_name}</p>}

      <table className="mt-4 w-full text-sm" aria-label="Invoice lines">
        <tbody className="divide-y divide-border">
          {invoice.lines.map((line) => (
            <tr key={line.id}>
              <td className="py-2 pr-4">
                {line.description}
                {line.quantity > 1 && <span className="text-muted"> × {line.quantity}</span>}
              </td>
              <td className="py-2 text-right tabular-nums">{formatMoney(line.amount, invoice.currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="mt-3 space-y-1 border-t border-border pt-3 text-sm">
        {invoice.tax > 0 && (
          <>
            <div className="flex justify-between">
              <dt className="text-muted">Subtotal</dt>
              <dd className="tabular-nums">{formatMoney(invoice.subtotal, invoice.currency)}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-muted">Tax</dt>
              <dd className="tabular-nums">{formatMoney(invoice.tax, invoice.currency)}</dd>
            </div>
          </>
        )}
        <div className="flex justify-between text-base font-semibold">
          <dt>Total</dt>
          <dd className="tabular-nums" data-testid="invoice-total">
            {formatMoney(invoice.total, invoice.currency)}
          </dd>
        </div>
      </dl>

      {invoice.description && <p className="mt-4 text-sm text-muted">{invoice.description}</p>}
      {invoice.due_date && invoice.status === "open" && <p className="mt-2 text-xs text-muted">Due {new Date(invoice.due_date).toLocaleDateString()}</p>}

      {confirming && (
        <p className="mt-4 text-sm text-muted" role="status">
          Confirming your payment…
        </p>
      )}
      {invoice.status === "paid" && (
        <p className="mt-4 flex items-center gap-2 text-sm font-medium text-success" role="status">
          <CheckCircle2 className="size-5" aria-hidden />
          Paid{invoice.paid_at ? ` on ${new Date(invoice.paid_at).toLocaleDateString()}` : ""}. Thank you.
        </p>
      )}
      {invoice.status === "void" && <p className="mt-4 text-sm text-muted">This invoice was canceled by the merchant. You do not need to pay it.</p>}

      {pay.isError && <Alert className="mt-4">We could not start the payment. Please try again.</Alert>}
      {invoice.payable && !confirming && (
        <Button className="mt-5 w-full" onClick={() => pay.mutate()} loading={pay.isPending}>
          Pay {formatMoney(invoice.total, invoice.currency)}
        </Button>
      )}
      <a href={`/invoices/${encodeURIComponent(token)}/pdf`} className="mt-4 block text-center text-sm font-medium text-brand-600 hover:underline">
        Download PDF
      </a>
    </Shell>
  );
}
