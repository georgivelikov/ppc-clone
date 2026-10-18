import { ApiError, type Payment } from "@cutpay/api-client";
import { Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { api } from "../../api";
import { durationText, percentText } from "../../lib/bnpl";

/**
 * For a payment that was paid in instalments, who financed it and what they kept; and for any payment a customer looked
 * at instalments for, why the router offered the providers it did.
 */
export function BnplPaymentCard({ payment }: { payment: Payment }) {
  const decision = useQuery({
    queryKey: ["bnpl-decision", payment.id],
    queryFn: () => api.getRoutingDecision(payment.id, payment.livemode),
    // A 404 only means the customer never looked at instalments.
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 1,
  });
  const financed = payment.bnpl ?? null;
  const explained = decision.data ?? null;
  if (!financed && !explained) return null;

  return (
    <Card aria-label="Instalments" className="md:col-span-2">
      <h2 className="font-semibold">{financed ? "Paid in instalments" : "Instalments were offered"}</h2>
      {financed && (
        <dl className="mt-2 divide-y divide-border" data-testid="bnpl-financed">
          <Line label="Financed by">{financed.provider}</Line>
          <Line label="Application">
            <span className="font-mono text-xs">{financed.application}</span>
          </Line>
          <Line label="Provider fee">
            <span data-testid="bnpl-fee">−{formatMoney(financed.fee, payment.currency)}</span>
          </Line>
          <Line label="You receive">
            <span data-testid="bnpl-net">{formatMoney(payment.amount - financed.fee, payment.currency)}</span>
          </Line>
        </dl>
      )}
      {explained && (
        <div className="mt-4" data-testid="bnpl-decision">
          <h3 className="text-sm font-medium">Why this provider?</h3>
          <p className="mt-1 text-sm" data-testid="bnpl-reason">
            {explained.reason}
          </p>
          {explained.ranked.length > 0 && (
            <table className="mt-3 w-full text-left text-sm">
              <thead className="border-b border-border text-xs uppercase text-muted">
                <tr>
                  <th className="py-2 pr-3 font-medium">#</th>
                  <th className="py-2 pr-3 font-medium">Provider</th>
                  <th className="py-2 pr-3 text-right font-medium">Fee</th>
                  <th className="py-2 pr-3 text-right font-medium">Approval</th>
                  <th className="py-2 text-right font-medium">Usual wait</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {explained.ranked.map((candidate) => (
                  <tr key={candidate.provider}>
                    <td className="py-2 pr-3">{candidate.rank}</td>
                    <td className="py-2 pr-3 font-medium">{candidate.name}</td>
                    <td className="py-2 pr-3 text-right tabular-nums">{formatMoney(candidate.best.merchant_fee, explained.currency)}</td>
                    <td className="py-2 pr-3 text-right tabular-nums">{percentText(candidate.stats.approval_rate)}</td>
                    <td className="py-2 text-right tabular-nums">{durationText(candidate.stats.p50_decision_ms)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {explained.excluded.length > 0 && (
            <ul className="mt-3 space-y-1 text-sm text-muted">
              {explained.excluded.map((left) => (
                <li key={left.provider}>
                  <span className="font-medium text-ink">{left.provider}</span> left out: {left.message}
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-xs text-muted">
            <Link to="/instalments" className="text-brand-700 hover:underline">
              Change how providers are chosen
            </Link>
          </p>
        </div>
      )}
    </Card>
  );
}

function Line({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-4 py-2 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
