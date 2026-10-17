import type { BalanceTransaction, BalanceTransactionType, MoneyAmount } from "@cutpay/api-client";
import { Button, Card, formatMoney, toMajorString } from "@cutpay/ui";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { useTestMode } from "../layout/testMode";

const PAGE_SIZE = 20;
const SUMMARY_DAYS = 14;

const TYPE_LABEL: Record<BalanceTransactionType, string> = {
  payment: "Payment",
  refund: "Refund",
  payout: "Payout",
  payout_failure: "Payout failed",
};

function amountIn(amounts: MoneyAmount[] | undefined, currency: string): number {
  return amounts?.find((entry) => entry.currency === currency)?.amount ?? 0;
}

/** Currencies other than the settlement currency in which the merchant holds (or owes) money. */
function otherCurrencies(lists: (MoneyAmount[] | undefined)[], main: string): string[] {
  const found = new Set<string>();
  for (const list of lists) {
    for (const entry of list ?? []) {
      if (entry.currency !== main && entry.amount !== 0) found.add(entry.currency);
    }
  }
  return [...found].sort();
}

function Amount({ minor, currency }: { minor: number; currency: string }) {
  return <span className={minor < 0 ? "text-danger" : undefined}>{formatMoney(minor, currency)}</span>;
}

function BalanceCard({ title, hint, minor, currency, testId }: {
  title: string;
  hint: string;
  minor: number;
  currency: string;
  testId: string;
}) {
  return (
    <Card>
      <p className="text-sm text-muted">{title}</p>
      <p className="mt-1 text-3xl font-semibold" data-testid={testId}>
        <Amount minor={minor} currency={currency} />
      </p>
      <p className="mt-1 text-xs text-muted">{hint}</p>
    </Card>
  );
}

function TransactionRow({ txn }: { txn: BalanceTransaction }) {
  const target = txn.type === "payment" ? `/payments/${txn.source_id}` : txn.parent_id ? `/payments/${txn.parent_id}` : null;
  const payout = txn.type === "payout" || txn.type === "payout_failure" ? `/payouts/${txn.source_id}` : null;
  const link = target ?? payout;
  return (
    <tr className="border-b border-border last:border-0 hover:bg-canvas">
      <td className="px-4 py-3 whitespace-nowrap text-muted">{new Date(txn.created).toLocaleString()}</td>
      <td className="px-4 py-3">{TYPE_LABEL[txn.type]}</td>
      <td className="max-w-56 truncate px-4 py-3">
        {link ? (
          <Link to={link} className="hover:underline">
            {txn.description}
          </Link>
        ) : (
          txn.description
        )}
      </td>
      <td className="px-4 py-3 text-right">
        <Amount minor={txn.gross} currency={txn.currency} />
      </td>
      <td className="px-4 py-3 text-right text-muted">
        {txn.fee === 0 ? "—" : formatMoney(-txn.fee, txn.currency)}
      </td>
      <td className="px-4 py-3 text-right font-medium">
        <Amount minor={txn.net} currency={txn.currency} />
      </td>
      <td className="px-4 py-3 whitespace-nowrap text-muted">
        {txn.available_on ? new Date(txn.available_on).toLocaleDateString() : "—"}
      </td>
    </tr>
  );
}

export function BalancesPage() {
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const currency = merchant.default_currency;

  const balance = useQuery({ queryKey: ["balance", livemode], queryFn: () => api.getBalance(livemode) });
  const summary = useQuery({
    queryKey: ["balance-summary", livemode],
    queryFn: () => api.balanceSummary(SUMMARY_DAYS, livemode),
  });
  const transactions = useInfiniteQuery({
    queryKey: ["balance-transactions", { livemode }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.listBalanceTransactions({ starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const rows = transactions.data?.pages.flatMap((page) => page.data) ?? [];

  const chartData = (summary.data?.data ?? [])
    .filter((day) => day.currency === currency)
    .map((day) => ({
      label: day.date.slice(5),
      volume: Number(toMajorString(day.volume, currency)),
      fees: Number(toMajorString(day.fees, currency)),
    }));
  const hasVolume = chartData.some((day) => day.volume > 0);
  const others = otherCurrencies([balance.data?.pending, balance.data?.available], currency);

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <h1 className="text-2xl font-semibold">Balances</h1>

      {balance.isError && <p className="text-sm text-danger">Could not load your balance.</p>}
      <div className="grid gap-4 md:grid-cols-2">
        <BalanceCard
          title="Available"
          hint="Ready to be paid out to your bank account."
          minor={amountIn(balance.data?.available, currency)}
          currency={currency}
          testId="balance-available"
        />
        <BalanceCard
          title="Pending"
          hint="Recent payments, available after the settlement delay."
          minor={amountIn(balance.data?.pending, currency)}
          currency={currency}
          testId="balance-pending"
        />
      </div>
      {others.length > 0 && (
        <Card>
          <h2 className="font-semibold">Other currencies</h2>
          <ul className="mt-2 divide-y divide-border text-sm">
            {others.map((code) => (
              <li key={code} className="flex justify-between py-2">
                <span className="text-muted">{code}</span>
                <span>
                  <Amount minor={amountIn(balance.data?.available, code)} currency={code} /> available ·{" "}
                  <Amount minor={amountIn(balance.data?.pending, code)} currency={code} /> pending
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card>
        <h2 className="font-semibold">Volume and fees, last {SUMMARY_DAYS} days</h2>
        {summary.isError && <p className="mt-2 text-sm text-danger">Could not load the chart.</p>}
        {summary.isSuccess && !hasVolume ? (
          <p className="mt-4 text-sm text-muted">No card payments in this period.</p>
        ) : (
          <div
            role="img"
            aria-label={`Daily card volume and fees in ${currency} for the last ${SUMMARY_DAYS} days`}
            className="mt-4 h-60"
          >
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chartData} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <CartesianGrid vertical={false} stroke="var(--color-border)" />
                <XAxis dataKey="label" tick={{ fontSize: 12 }} stroke="var(--color-muted)" />
                <YAxis tick={{ fontSize: 12 }} stroke="var(--color-muted)" width={56} />
                <Tooltip formatter={(value) => formatMoney(Math.round(Number(value) * 100), currency)} />
                <Legend />
                <Bar dataKey="volume" name="Card volume" fill="var(--color-brand-500)" radius={[4, 4, 0, 0]} />
                <Bar dataKey="fees" name="Fees" fill="var(--color-warning)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </Card>

      <div className="space-y-3">
        <h2 className="text-lg font-semibold">Transactions</h2>
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-muted uppercase">
              <tr>
                <th className="px-4 py-3 font-medium">Date</th>
                <th className="px-4 py-3 font-medium">Type</th>
                <th className="px-4 py-3 font-medium">Description</th>
                <th className="px-4 py-3 text-right font-medium">Gross</th>
                <th className="px-4 py-3 text-right font-medium">Fee</th>
                <th className="px-4 py-3 text-right font-medium">Net</th>
                <th className="px-4 py-3 font-medium">Available on</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((txn) => (
                <TransactionRow key={txn.id} txn={txn} />
              ))}
            </tbody>
          </table>
          {transactions.isPending && <p className="px-4 py-6 text-sm text-muted">Loading transactions…</p>}
          {transactions.isError && <p className="px-4 py-6 text-sm text-danger">Could not load transactions.</p>}
          {transactions.isSuccess && rows.length === 0 && (
            <p className="px-4 py-10 text-center text-sm text-muted">
              No transactions yet. They appear here once a payment succeeds.
            </p>
          )}
        </Card>
        {transactions.hasNextPage && (
          <div className="text-center">
            <Button
              variant="secondary"
              loading={transactions.isFetchingNextPage}
              onClick={() => void transactions.fetchNextPage()}
            >
              Load more
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
