import type { MonthChurn } from "@cutpay/api-client";
import { Card } from "@cutpay/ui";
import { churnPercent } from "../lib/billing";

const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-10" as "Oct 2026". */
export function monthLabel(month: string): string {
  const [year, number] = month.split("-");
  return `${MONTH_NAMES[Number(number) - 1] ?? number} ${year}`;
}

/**
 * Churn month by month: of the subscriptions that existed when a month began, the share that ended during it.
 * Each month is a row with a bar, and the figures are in the text so nothing depends on seeing the bar.
 */
export function ChurnChart({ months }: { months: MonthChurn[] }) {
  const shown = months.filter((m) => m.at_start > 0 || m.ended > 0);
  const scale = Math.max(5, ...shown.map(churnPercent));

  return (
    <Card aria-label="Churn by month">
      <h2 className="font-semibold">Churn by month</h2>
      <p className="mt-1 text-sm text-muted">Of the subscriptions that existed when the month began, the share that ended during it.</p>
      {shown.length === 0 ? (
        <p className="mt-3 text-sm text-muted">Nothing to show until there are subscriptions.</p>
      ) : (
        <ul className="mt-3 space-y-2">
          {shown.map((m) => {
            const percent = churnPercent(m);
            return (
              <li key={m.month} className="grid grid-cols-[5.5rem_1fr_auto] items-center gap-3 text-sm" data-testid={`churn-${m.month}`}>
                <span className="text-muted">{monthLabel(m.month)}</span>
                <span className="h-3 overflow-hidden rounded bg-canvas" aria-hidden>
                  <span className="block h-full rounded bg-brand-600" style={{ width: `${(percent / scale) * 100}%` }} />
                </span>
                <span className="tabular-nums">
                  {percent.toFixed(1)}% <span className="text-muted">({m.ended} of {m.at_start})</span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
