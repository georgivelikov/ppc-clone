import { Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../api";
import { useTestMode } from "../../layout/testMode";

const METHOD_NAME: Record<string, string> = {
  card: "Card",
  apple_pay: "Apple Pay",
  google_pay: "Google Pay",
  blik: "BLIK",
  bnpl: "Instalments",
};

const REASON_NAME: Record<string, string> = {
  card_declined: "Card declined",
  insufficient_funds: "Insufficient funds",
  expired_card: "Expired card",
  processing_error: "Processing error",
  invalid_code: "Invalid code",
};

function Bar({ share }: { share: number }) {
  return (
    <div className="h-2 flex-1 rounded-full bg-canvas" aria-hidden>
      <div className="h-2 rounded-full bg-brand-600" style={{ width: `${Math.max(2, Math.round(share * 100))}%` }} />
    </div>
  );
}

/** What the last weeks looked like: money, how often paying works, why it does not, how people pay, where they give up. */
export function Insights({ days = 30 }: { days?: number }) {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const overview = useQuery({ queryKey: ["overview", days, livemode], queryFn: () => api.getOverview(days, livemode), retry: false });
  const o = overview.data;
  if (!o) return null; // the numbers are a bonus: no message when they are not there (yet)

  const mixTotal = o.method_mix.reduce((sum, m) => sum + m.count, 0);
  const reasonMax = Math.max(1, ...o.decline_reasons.map((r) => r.count));
  const { opened, attempted, paid } = o.funnel;
  return (
    <section aria-label={`The last ${days} days`} className="space-y-4" data-testid="insights">
      <h2 className="text-lg font-semibold">The last {days} days</h2>
      {o.money.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {o.money.map((m) => (
            <Card key={m.currency} data-testid={`insights-money-${m.currency}`}>
              <p className="text-sm text-muted">Sales in {m.currency}</p>
              <p className="mt-1 text-2xl font-semibold">{formatMoney(m.gross, m.currency)}</p>
              <dl className="mt-3 space-y-1 text-xs text-muted">
                <div className="flex justify-between"><dt>Fees</dt><dd>{formatMoney(m.fees, m.currency)}</dd></div>
                <div className="flex justify-between"><dt>Refunds</dt><dd>{formatMoney(m.refunds, m.currency)}</dd></div>
                <div className="flex justify-between"><dt>Disputes</dt><dd>{formatMoney(m.disputes, m.currency)}</dd></div>
                <div className="flex justify-between font-medium text-ink"><dt>Net</dt><dd>{formatMoney(m.net, m.currency)}</dd></div>
              </dl>
            </Card>
          ))}
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <p className="text-sm text-muted">Payments that worked</p>
          <p className="mt-1 text-2xl font-semibold" data-testid="success-rate">
            {o.success_rate === null ? "-" : `${Math.round(o.success_rate * 100)}%`}
          </p>
          <p className="text-xs text-muted">
            {o.succeeded} succeeded, {o.failed} failed
          </p>
          {o.decline_reasons.length > 0 && (
            <ul className="mt-3 space-y-1.5" aria-label="Why payments failed">
              {o.decline_reasons.map((r) => (
                <li key={r.key} className="flex items-center gap-2 text-xs">
                  <span className="w-36 shrink-0">{REASON_NAME[r.key] ?? r.key}</span>
                  <Bar share={r.count / reasonMax} />
                  <span className="w-6 text-right text-muted">{r.count}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card>
          <p className="text-sm text-muted">How customers paid</p>
          {o.method_mix.length === 0 ? (
            <p className="mt-2 text-sm text-muted">No payments yet.</p>
          ) : (
            <ul className="mt-3 space-y-1.5" aria-label="Payment methods">
              {o.method_mix.map((m) => (
                <li key={m.key} className="flex items-center gap-2 text-xs">
                  <span className="w-24 shrink-0">{METHOD_NAME[m.key] ?? m.key}</span>
                  <Bar share={m.count / mixTotal} />
                  <span className="w-10 text-right text-muted">{Math.round((m.count / mixTotal) * 100)}%</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
      <Card>
        <p className="text-sm text-muted">From checkout to paid</p>
        <ol className="mt-3 grid grid-cols-3 gap-3 text-center" aria-label="Checkout funnel">
          <li>
            <p className="text-xl font-semibold" data-testid="funnel-opened">{opened}</p>
            <p className="text-xs text-muted">opened a checkout</p>
          </li>
          <li>
            <p className="text-xl font-semibold" data-testid="funnel-attempted">{attempted}</p>
            <p className="text-xs text-muted">tried to pay{opened > 0 ? ` (${Math.round((attempted / opened) * 100)}%)` : ""}</p>
          </li>
          <li>
            <p className="text-xl font-semibold" data-testid="funnel-paid">{paid}</p>
            <p className="text-xs text-muted">paid{opened > 0 ? ` (${Math.round((paid / opened) * 100)}%)` : ""}</p>
          </li>
        </ol>
      </Card>
    </section>
  );
}
