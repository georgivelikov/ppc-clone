import type { Dispute, DisputeStatus } from "@cutpay/api-client";
import { Badge, Card, formatMoney } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { api } from "../../api";
import { useTestMode } from "../../layout/testMode";
import { Button } from "@cutpay/ui";

const PAGE_SIZE = 20;

export const DISPUTE_STATUS: Record<DisputeStatus, { label: string; tone: "neutral" | "success" | "warning" | "danger" }> = {
  needs_response: { label: "Needs response", tone: "danger" },
  under_review: { label: "Under review", tone: "warning" },
  won: { label: "Won", tone: "success" },
  lost: { label: "Lost", tone: "neutral" },
};

export function DisputeBadge({ status }: { status: DisputeStatus }) {
  return <Badge tone={DISPUTE_STATUS[status].tone}>{DISPUTE_STATUS[status].label}</Badge>;
}

/** Customers who asked their bank for money back, and what to do about each. */
export function DisputesPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [status, setStatus] = useState<DisputeStatus | "">("");
  const disputes = useInfiniteQuery({
    queryKey: ["disputes", { livemode, status }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.listDisputes({ livemode, status: status || undefined, starting_after: pageParam, limit: PAGE_SIZE }),
    getNextPageParam: (last) => (last.has_more ? last.data[last.data.length - 1]?.id : undefined),
  });
  const rows: Dispute[] = disputes.data?.pages.flatMap((p) => p.data) ?? [];

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Disputes</h1>
        <p className="mt-1 text-sm text-muted">
          When a customer asks their bank for the money back, the amount and a fee are held until the card network decides. Send
          evidence before the deadline to try to win it back.
        </p>
      </div>
      <Card>
        <label className="flex items-center gap-2 text-sm">
          Show
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value as DisputeStatus | "")}
            className="rounded-md border border-border bg-surface px-2 py-1"
            aria-label="Status"
          >
            <option value="">All</option>
            {Object.entries(DISPUTE_STATUS).map(([value, info]) => (
              <option key={value} value={value}>
                {info.label}
              </option>
            ))}
          </select>
        </label>
        {disputes.isError && <p className="mt-3 text-sm text-danger">Could not load the disputes.</p>}
        {disputes.isSuccess && rows.length === 0 && (
          <p className="mt-4 text-sm text-muted">
            No disputes. To see one, pay with the test card 4000 0000 0000 0259 and move the clock a day.
          </p>
        )}
        <ul className="mt-4 divide-y divide-border" aria-label="Disputes">
          {rows.map((d) => (
            <li key={d.id} data-testid="dispute-row">
              <Link to={`/disputes/${d.id}`} className="flex flex-wrap items-center justify-between gap-3 py-3 hover:bg-canvas">
                <div>
                  <p className="text-sm font-medium">
                    {formatMoney(d.amount, d.currency)} <span className="text-muted">· {d.payment_intent}</span>
                  </p>
                  <p className="text-xs text-muted">
                    Opened {new Date(d.created).toLocaleDateString()}
                    {d.status === "needs_response" && ` · answer by ${new Date(d.evidence_due_by).toLocaleDateString()}`}
                  </p>
                </div>
                <DisputeBadge status={d.status} />
              </Link>
            </li>
          ))}
        </ul>
        {disputes.hasNextPage && (
          <Button variant="secondary" className="mt-3" loading={disputes.isFetchingNextPage} onClick={() => void disputes.fetchNextPage()}>
            Load more
          </Button>
        )}
      </Card>
    </div>
  );
}
