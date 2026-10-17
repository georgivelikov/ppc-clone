import { ApiError } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Printer } from "lucide-react";
import { Link, useParams } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";

const BRAND: Record<string, string> = { visa: "Visa", mastercard: "Mastercard", amex: "American Express" };

/** A receipt for a terminal payment. Printing hides everything but the card. */
export function ReceiptPage() {
  const { id = "" } = useParams();
  const { merchant } = useSession();
  const { data: payment, error, isPending } = useQuery({ queryKey: ["payment", id], queryFn: () => api.getPayment(id) });

  if (isPending) return <p className="text-sm text-muted">Loading…</p>;
  if (error || !payment) {
    return <Alert>{error instanceof ApiError && error.status === 404 ? "That payment does not exist." : "Could not load the receipt."}</Alert>;
  }
  const attempt = payment.attempts.at(-1);
  const paid = payment.status === "succeeded" || payment.status === "partially_refunded" || payment.status === "refunded";

  return (
    <div className="mx-auto max-w-md space-y-4">
      <Card className="space-y-4 print:border-0 print:p-0" aria-label="Receipt">
        <div className="text-center">
          <CheckCircle2 className={paid ? "mx-auto size-10 text-success print:hidden" : "mx-auto size-10 text-muted print:hidden"} aria-hidden />
          <h1 className="mt-2 text-xl font-semibold">{merchant.display_name}</h1>
          <p className="text-sm text-muted">{paid ? "Receipt" : `Payment ${payment.status.replaceAll("_", " ")}`}</p>
        </div>
        <p className="text-center text-4xl font-semibold tabular-nums" data-testid="receipt-amount">
          {formatMoney(payment.amount, payment.currency)}
        </p>
        <dl className="grid grid-cols-[8rem_1fr] gap-y-2 border-t border-border pt-4 text-sm">
          <dt className="text-muted">Date</dt>
          <dd>{new Date(payment.created_at).toLocaleString()}</dd>
          {payment.description && (
            <>
              <dt className="text-muted">For</dt>
              <dd>{payment.description}</dd>
            </>
          )}
          {attempt?.card_last4 && (
            <>
              <dt className="text-muted">Card</dt>
              <dd>
                {BRAND[attempt.card_brand ?? ""] ?? attempt.card_brand ?? "Card"} •••• {attempt.card_last4}
              </dd>
            </>
          )}
          <dt className="text-muted">Method</dt>
          <dd>{payment.channel === "moto" ? "Card not present (phone or mail order)" : "Online"}</dd>
          <dt className="text-muted">Reference</dt>
          <dd className="break-all font-mono text-xs">{payment.id}</dd>
          {payment.customer_email && (
            <>
              <dt className="text-muted">Customer</dt>
              <dd>{payment.customer_email}</dd>
            </>
          )}
        </dl>
        {!payment.livemode && <p className="text-center text-xs text-warning">Test payment — no real money moved.</p>}
      </Card>
      <div className="flex justify-center gap-2 print:hidden">
        <Button onClick={() => window.print()}>
          <Printer className="size-4" aria-hidden />
          Print receipt
        </Button>
        <Link to="/terminal">
          <Button variant="secondary">New charge</Button>
        </Link>
      </div>
    </div>
  );
}
