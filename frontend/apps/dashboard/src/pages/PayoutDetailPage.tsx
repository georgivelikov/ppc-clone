import { ApiError } from "@cutpay/api-client";
import { Alert, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { Link, useParams } from "react-router";
import { api } from "../api";
import { PAYOUT_FAILURE_LABEL, PayoutStatusBadge } from "../components/PayoutStatusBadge";

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-4 py-2 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-medium break-all">{children}</dd>
    </div>
  );
}

/** A payout's items are ledger entries: payments and refunds link back to their payment page. */
function ItemSource({ sourceId }: { sourceId: string }) {
  if (sourceId.startsWith("pay_")) {
    return (
      <Link to={`/payments/${sourceId}`} className="hover:underline">
        {sourceId}
      </Link>
    );
  }
  return <span>{sourceId}</span>;
}

export function PayoutDetailPage() {
  const { id = "" } = useParams();
  const detail = useQuery({ queryKey: ["payout", id], queryFn: () => api.getPayout(id) });
  const reconciliation = useQuery({
    queryKey: ["payout-reconciliation", id],
    queryFn: () => api.payoutReconciliation(id),
  });

  if (detail.isPending) return <p className="text-sm text-muted">Loading payout…</p>;
  if (detail.isError || !detail.data) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Link to="/payouts" className="text-sm text-brand-600 hover:underline">
          ← Payouts
        </Link>
        <Alert>
          {detail.error instanceof ApiError && detail.error.status === 404
            ? "Payout not found."
            : "Could not load the payout."}
        </Alert>
      </div>
    );
  }

  const { payout, items } = detail.data;
  const rec = reconciliation.data;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link to="/payouts" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Payouts
      </Link>

      <div>
        <h1 className="text-3xl font-semibold" data-testid="payout-amount">
          {formatMoney(payout.amount, payout.currency)}
        </h1>
        <p className="mt-1 flex items-center gap-2 text-sm text-muted">
          <PayoutStatusBadge status={payout.status} />
          <span>{payout.id}</span>
        </p>
      </div>

      {payout.status === "failed" && (
        <Alert>
          The bank rejected this payout.{" "}
          {payout.failure_code ? (PAYOUT_FAILURE_LABEL[payout.failure_code] ?? payout.failure_code) : ""} The money
          is back in your available balance and goes out with your next payout.
        </Alert>
      )}

      <Card>
        <h2 className="font-semibold">Details</h2>
        <dl className="mt-2 divide-y divide-border">
          <Row label="Type">{payout.kind === "instant" ? "Instant (manual)" : "Scheduled"}</Row>
          <Row label="Expected arrival">{new Date(`${payout.arrival_date}T00:00:00`).toLocaleDateString()}</Row>
          <Row label="Created">{new Date(payout.created).toLocaleString()}</Row>
          {payout.fee > 0 && <Row label="Instant payout fee">{formatMoney(payout.fee, payout.currency)}</Row>}
        </dl>
      </Card>

      <Card className="overflow-x-auto p-0">
        <div className="flex items-center justify-between gap-4 px-4 py-3">
          <h2 className="font-semibold">Included transactions</h2>
          {rec && (
            <p
              data-testid="reconciliation"
              className={rec.balanced ? "text-sm text-success" : "text-sm text-danger"}
            >
              {rec.balanced
                ? `Reconciled: ${rec.item_count} transactions add up to ${formatMoney(rec.items_total, payout.currency)}`
                : `Does not reconcile: transactions add up to ${formatMoney(rec.items_total, payout.currency)}`}
            </p>
          )}
        </div>
        <table className="w-full text-left text-sm">
          <thead className="border-y border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Source</th>
              <th className="px-4 py-3 font-medium">Ledger entry</th>
              <th className="px-4 py-3 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.journal_entry_ref} className="border-b border-border last:border-0">
                <td className="px-4 py-3">
                  <ItemSource sourceId={item.source_id} />
                </td>
                <td className="px-4 py-3 text-muted">{item.journal_entry_ref}</td>
                <td className={item.amount < 0 ? "px-4 py-3 text-right text-danger" : "px-4 py-3 text-right"}>
                  {formatMoney(item.amount, payout.currency)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {items.length === 0 && <p className="px-4 py-6 text-sm text-muted">No transactions.</p>}
      </Card>
    </div>
  );
}
