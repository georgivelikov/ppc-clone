import type { BalanceTransaction, MoneyAmount } from "@cutpay/api-client";
import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Button, Card, toMajorString } from "@cutpay/ui";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { useTestMode } from "../layout/testMode";
import { ConvertDialog } from "./fx/ConvertDialog";
import { CurrencySettingsCard } from "./fx/CurrencySettingsCard";
import { FxReportCard } from "./fx/FxReportCard";

const PAGE_SIZE = 20;
const SUMMARY_DAYS = 14;

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
  const { money } = useLocaleFormat();
  return <span className={minor < 0 ? "text-danger" : undefined}>{money(minor, currency)}</span>;
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
  const { t } = useTranslation();
  const { money, date, dateTime } = useLocaleFormat();
  const target = txn.type === "payment" ? `/payments/${txn.source_id}` : txn.parent_id ? `/payments/${txn.parent_id}` : null;
  const payout = txn.type === "payout" || txn.type === "payout_failure" ? `/payouts/${txn.source_id}` : null;
  const link = target ?? payout;
  return (
    <tr className="border-b border-border last:border-0 hover:bg-canvas">
      <td className="px-4 py-3 whitespace-nowrap text-muted">{dateTime(txn.created)}</td>
      <td className="px-4 py-3">{t(`balances.types.${txn.type}`)}</td>
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
        {txn.fee === 0 ? "—" : money(-txn.fee, txn.currency)}
      </td>
      <td className="px-4 py-3 text-right font-medium">
        <Amount minor={txn.net} currency={txn.currency} />
      </td>
      <td className="px-4 py-3 whitespace-nowrap text-muted">
        {txn.available_on ? date(txn.available_on) : "—"}
      </td>
    </tr>
  );
}

export function BalancesPage() {
  const { t } = useTranslation();
  const { money } = useLocaleFormat();
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
  const [converting, setConverting] = useState<string | null>(null);
  const convertible = (code: string) => amountIn(balance.data?.available, code) > 0;

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <h1 className="text-2xl font-semibold">{t("balances.title")}</h1>

      {balance.isError && <p className="text-sm text-danger">{t("balances.loadFailed")}</p>}
      <div className="grid gap-4 md:grid-cols-2">
        <BalanceCard
          title={t("balances.available")}
          hint={t("balances.availableHint")}
          minor={amountIn(balance.data?.available, currency)}
          currency={currency}
          testId="balance-available"
        />
        <BalanceCard
          title={t("balances.pending")}
          hint={t("balances.pendingHint")}
          minor={amountIn(balance.data?.pending, currency)}
          currency={currency}
          testId="balance-pending"
        />
      </div>
      {others.length > 0 && (
        <Card>
          <h2 className="font-semibold">{t("balances.other")}</h2>
          <ul className="mt-2 divide-y divide-border text-sm">
            {others.map((code) => (
              <li key={code} className="flex items-center justify-between gap-3 py-2" data-testid={`balance-${code}`}>
                <span className="text-muted">{code}</span>
                <span className="flex items-center gap-3">
                  <span>
                    {t("balances.availableAmount", { amount: money(amountIn(balance.data?.available, code), code) })} ·{" "}
                    {t("balances.pendingAmount", { amount: money(amountIn(balance.data?.pending, code), code) })}
                  </span>
                  {convertible(code) && (
                    <Button variant="secondary" size="sm" onClick={() => setConverting(code)} aria-label={t("balances.convertCurrency", { currency: code })}>
                      {t("balances.convert")}
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {convertible(currency) && (
        <div className="flex justify-end">
          <Button variant="secondary" size="sm" onClick={() => setConverting(currency)} aria-label={t("balances.convertCurrency", { currency })}>
            {t("balances.convertCurrencyMore", { currency })}
          </Button>
        </div>
      )}
      {converting && (
        <ConvertDialog
          from={converting}
          available={amountIn(balance.data?.available, converting)}
          preferred={converting === currency ? others[0] ?? "EUR" : currency}
          onClose={() => setConverting(null)}
        />
      )}

      <FxReportCard />
      <CurrencySettingsCard />

      <Card>
        <h2 className="font-semibold">{t("balances.chartTitle", { days: SUMMARY_DAYS })}</h2>
        {summary.isError && <p className="mt-2 text-sm text-danger">{t("balances.chartFailed")}</p>}
        {summary.isSuccess && !hasVolume ? (
          <p className="mt-4 text-sm text-muted">{t("balances.chartEmpty")}</p>
        ) : (
          <div
            role="img"
            aria-label={t("balances.chartLabel", { currency, days: SUMMARY_DAYS })}
            className="mt-4 h-60"
          >
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chartData} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <CartesianGrid vertical={false} stroke="var(--color-border)" />
                <XAxis dataKey="label" tick={{ fontSize: 12 }} stroke="var(--color-muted)" />
                <YAxis tick={{ fontSize: 12 }} stroke="var(--color-muted)" width={56} />
                <Tooltip formatter={(value) => money(Math.round(Number(value) * 100), currency)} />
                <Legend />
                <Bar dataKey="volume" name={t("balances.cardVolume")} fill="var(--color-brand-500)" radius={[4, 4, 0, 0]} />
                <Bar dataKey="fees" name={t("balances.fees")} fill="var(--color-warning)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </Card>

      <div className="space-y-3">
        <h2 className="text-lg font-semibold">{t("balances.transactions")}</h2>
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-muted uppercase">
              <tr>
                <th className="px-4 py-3 font-medium">{t("balances.cols.date")}</th>
                <th className="px-4 py-3 font-medium">{t("balances.cols.type")}</th>
                <th className="px-4 py-3 font-medium">{t("balances.cols.description")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("balances.cols.gross")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("balances.cols.fee")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("balances.cols.net")}</th>
                <th className="px-4 py-3 font-medium">{t("balances.cols.availableOn")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((txn) => (
                <TransactionRow key={txn.id} txn={txn} />
              ))}
            </tbody>
          </table>
          {transactions.isPending && <p className="px-4 py-6 text-sm text-muted">{t("balances.loadingTransactions")}</p>}
          {transactions.isError && <p className="px-4 py-6 text-sm text-danger">{t("balances.transactionsFailed")}</p>}
          {transactions.isSuccess && rows.length === 0 && (
            <p className="px-4 py-10 text-center text-sm text-muted">
              {t("balances.noTransactions")}
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
              {t("balances.loadMore")}
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
