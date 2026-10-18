import { ApiError, type Payment } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api";
import { StatusBadge } from "../components/StatusBadge";
import { BnplPaymentCard } from "./bnpl/BnplPaymentCard";
import { CaptureDialog } from "./CaptureDialog";
import { RefundDialog } from "./RefundDialog";

/** Statuses that mean the customer's money was captured and the ledger has (or will have) a fee for it. */
const SETTLED_STATUSES: Payment["status"][] = ["succeeded", "partially_refunded", "refunded"];

interface TimelineEvent {
  at: string;
  title: string;
  detail?: string;
}

/** Builds a readable history from what the payment recorded: attempts, 3DS, outcome and refunds. */
export function buildTimeline(payment: Payment): TimelineEvent[] {
  const events: TimelineEvent[] = [{ at: payment.created_at, title: "Payment created" }];
  for (const attempt of payment.attempts) {
    const card = attempt.method_type === "bnpl" ? "instalments" : `${attempt.card_brand ?? "Card"} •••• ${attempt.card_last4 ?? "????"}`;
    events.push({ at: attempt.created_at, title: attempt.method_type === "bnpl" ? "Approved for instalments" : `Payment attempted with ${card}` });
    if (attempt.three_ds_status) {
      events.push({ at: attempt.created_at, title: "3-D Secure authentication required" });
    }
  }
  const final = payment.attempts.at(-1);
  if (payment.status === "failed") {
    events.push({
      at: payment.updated_at,
      title: "Payment failed",
      detail: payment.failure_message ?? payment.failure_code ?? undefined,
    });
  } else if (payment.status === "canceled") {
    events.push({
      at: payment.updated_at,
      title: payment.failure_code === "authorization_expired" ? "Hold released: not captured in time" : "Payment canceled",
    });
  } else if (payment.status === "requires_capture") {
    events.push({
      at: payment.updated_at,
      title: `${formatMoney(payment.amount, payment.currency)} held on the card`,
      detail: "Waiting to be captured",
    });
  } else if (final?.status === "succeeded") {
    events.push({
      at: payment.updated_at,
      title: "Payment succeeded",
      detail:
        payment.amount_authorized !== null && payment.amount_authorized > payment.amount
          ? `Captured ${formatMoney(payment.amount, payment.currency)} of ${formatMoney(payment.amount_authorized, payment.currency)} held; the rest was released`
          : undefined,
    });
  }
  for (const refund of payment.refunds) {
    events.push({
      at: refund.created_at,
      title: `Refund of ${formatMoney(refund.amount, refund.currency)} ${refund.status}`,
      detail: refund.reason ?? undefined,
    });
  }
  return events.sort((a, b) => a.at.localeCompare(b.at));
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-4 py-2 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-medium break-all">{children}</dd>
    </div>
  );
}

export function PaymentDetailPage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const [refunding, setRefunding] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const { data: payment, error, isPending } = useQuery({ queryKey: ["payment", id], queryFn: () => api.getPayment(id) });

  // The ledger prices a payment shortly after it succeeds, so the fee can lag behind the status.
  const settled = payment !== undefined && SETTLED_STATUSES.includes(payment.status);
  const ledger = useQuery({
    queryKey: ["balance-transactions", "source", id],
    queryFn: () => api.listBalanceTransactions({ source: id, limit: 50, livemode: payment?.livemode }),
    enabled: settled,
    // Keep checking until the fee shows up, without hammering the service.
    refetchInterval: (query) => (query.state.data?.data.some((txn) => txn.type === "payment") ? false : 3000),
  });
  const paymentTxn = ledger.data?.data.find((txn) => txn.type === "payment");

  const cancel = useMutation({
    mutationFn: () => api.cancelPayment(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["payment", id] });
      await queryClient.invalidateQueries({ queryKey: ["payments"] });
    },
  });

  if (isPending) return <p className="text-sm text-muted">Loading payment…</p>;
  if (error || !payment) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Link to="/payments" className="text-sm text-brand-600 hover:underline">
          ← Payments
        </Link>
        <Alert>{error instanceof ApiError && error.status === 404 ? "Payment not found." : "Could not load the payment."}</Alert>
      </div>
    );
  }

  const attempt = payment.attempts.at(-1);
  const canRefund =
    (payment.status === "succeeded" || payment.status === "partially_refunded") &&
    payment.amount > payment.amount_refunded;
  const canCancel = payment.status === "requires_payment_method" || payment.status === "requires_action";
  const canCapture = payment.status === "requires_capture";

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link to="/payments" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Payments
      </Link>

      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-3xl font-semibold" data-testid="payment-amount">
            {formatMoney(payment.amount, payment.currency)}
          </h1>
          <p className="mt-1 flex items-center gap-2 text-sm text-muted">
            <StatusBadge status={payment.status} />
            <span>{payment.id}</span>
          </p>
        </div>
        <div className="flex gap-3">
          {(canCancel || canCapture) && (
            <Button variant="secondary" loading={cancel.isPending} onClick={() => cancel.mutate()}>
              {canCapture ? "Release hold" : "Cancel payment"}
            </Button>
          )}
          {canCapture && <Button onClick={() => setCapturing(true)}>Capture</Button>}
          {canRefund && <Button onClick={() => setRefunding(true)}>Refund</Button>}
        </div>
      </div>
      {cancel.isError && <Alert>{cancel.error instanceof ApiError ? cancel.error.message : "Could not cancel."}</Alert>}

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="font-semibold">Details</h2>
          <dl className="mt-2 divide-y divide-border">
            <Row label="Description">{payment.description ?? "—"}</Row>
            <Row label="Customer">{payment.customer_email ?? "—"}</Row>
            <Row label="Channel">
              <span data-testid="payment-channel">
                {payment.channel === "moto" ? "Card not present (MOTO)" : "Online"}
              </span>
            </Row>
            {payment.amount_authorized !== null && (
              <Row label={canCapture ? "Held on card" : "Authorised"}>
                <span data-testid="payment-authorized">{formatMoney(payment.amount_authorized, payment.currency)}</span>
              </Row>
            )}
            {canCapture && payment.capture_before && (
              <Row label="Released automatically">
                <span data-testid="payment-capture-before">{new Date(payment.capture_before).toLocaleString()}</span>
              </Row>
            )}
            <Row label="Created">{new Date(payment.created_at).toLocaleString()}</Row>
            {payment.amount_refunded > 0 && (
              <Row label="Refunded">{formatMoney(payment.amount_refunded, payment.currency)}</Row>
            )}
            {payment.failure_message && <Row label="Failure">{payment.failure_message}</Row>}
            {(payment.transfer_data ?? []).map((transfer) => (
              <Row key={transfer.destination} label="Sent to">
                <Link to={`/connected-accounts/${transfer.destination}`} className="text-brand-700 hover:underline" data-testid="payment-transfer">
                  {transfer.destination} · {formatMoney(transfer.amount, payment.currency)}
                </Link>
              </Row>
            ))}
            {payment.application_fee_amount != null && (
              <Row label="Platform fee">{formatMoney(payment.application_fee_amount, payment.currency)}</Row>
            )}
          </dl>
          {settled && (
            <dl className="mt-2 divide-y divide-border border-t border-border">
              <Row label="Fee">
                <span data-testid="payment-fee">
                  {paymentTxn ? `−${formatMoney(paymentTxn.fee, payment.currency)}` : ledger.isError ? "—" : "Calculating…"}
                </span>
              </Row>
              <Row label="Net">
                <span data-testid="payment-net">
                  {paymentTxn ? formatMoney(paymentTxn.net, payment.currency) : "—"}
                </span>
              </Row>
              {paymentTxn?.available_on && (
                <Row label="Available on">{new Date(paymentTxn.available_on).toLocaleDateString()}</Row>
              )}
            </dl>
          )}
        </Card>
        <Card>
          <h2 className="font-semibold">Payment method</h2>
          {attempt?.method_type === "bnpl" ? (
            <p className="mt-2 text-sm" data-testid="method-instalments">
              Paid in instalments{payment.bnpl ? ` with ${payment.bnpl.provider}` : ""}.
            </p>
          ) : attempt ? (
            <dl className="mt-2 divide-y divide-border">
              <Row label="Card">
                <span data-testid="card-summary">
                  {attempt.card_brand} •••• {attempt.card_last4}
                </span>
              </Row>
              <Row label="Expires">
                {attempt.card_exp_month}/{attempt.card_exp_year}
              </Row>
              <Row label="3-D Secure">{attempt.three_ds_status?.replace("_", " ") ?? "Not required"}</Row>
            </dl>
          ) : (
            <p className="mt-2 text-sm text-muted">The customer has not entered a card yet.</p>
          )}
        </Card>
      </div>

      <BnplPaymentCard payment={payment} />

      <Card>
        <h2 className="font-semibold">Timeline</h2>
        <ol className="mt-3 space-y-3">
          {buildTimeline(payment).map((event, index) => (
            <li key={index} className="flex gap-3 text-sm">
              <span className="mt-1.5 size-2 shrink-0 rounded-full bg-brand-500" aria-hidden />
              <div>
                <p className="font-medium">{event.title}</p>
                {event.detail && <p className="text-muted">{event.detail}</p>}
                <p className="text-xs text-muted">{new Date(event.at).toLocaleString()}</p>
              </div>
            </li>
          ))}
        </ol>
      </Card>

      {payment.refunds.length > 0 && (
        <Card>
          <h2 className="font-semibold">Refunds</h2>
          <ul className="mt-2 divide-y divide-border">
            {payment.refunds.map((refund) => (
              <li key={refund.id} className="flex items-center justify-between py-2 text-sm">
                <span>
                  {refund.id}
                  {refund.reason && <span className="text-muted"> · {refund.reason}</span>}
                </span>
                <span className="font-medium">
                  {formatMoney(refund.amount, refund.currency)} · {refund.status}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card>
        <details>
          <summary className="cursor-pointer font-semibold">Raw JSON</summary>
          <pre className="mt-3 overflow-x-auto rounded-lg bg-canvas p-3 text-xs">{JSON.stringify(payment, null, 2)}</pre>
        </details>
      </Card>

      {refunding && <RefundDialog payment={payment} onClose={() => setRefunding(false)} />}
      {capturing && <CaptureDialog payment={payment} onClose={() => setCapturing(false)} />}
    </div>
  );
}
