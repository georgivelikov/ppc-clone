import type { InvoiceStatus } from "@cutpay/api-client";
import { Button, Card, formatMoney } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { api } from "../../api";
import { INVOICE_LABEL, InvoiceStatusBadge } from "../../components/BillingBadges";
import { useTestMode } from "../../layout/testMode";

const PAGE_SIZE = 20;

export function InvoicesPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [status, setStatus] = useState<InvoiceStatus | "">("");
  const query = useInfiniteQuery({
    queryKey: ["invoices", { livemode, status }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listInvoices({ status, starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const invoices = query.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Invoices</h1>
          <p className="mt-1 text-sm text-muted">Every subscription invoice, and the one-off ones you send.</p>
        </div>
        <Link to="/invoices/new">
          <Button>
            <Plus className="size-4" aria-hidden />
            Create invoice
          </Button>
        </Link>
      </div>

      <select aria-label="Filter by status" value={status} onChange={(e) => setStatus(e.target.value as InvoiceStatus | "")} className="h-10 rounded-lg border border-border bg-surface px-3 text-sm">
        <option value="">All statuses</option>
        {Object.entries(INVOICE_LABEL).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Invoice</th>
              <th className="px-4 py-3 font-medium">Customer</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 text-right font-medium">Total</th>
              <th className="px-4 py-3 font-medium">Created</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {invoices.map((invoice) => (
              <tr key={invoice.id} className="hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/invoices/${invoice.id}`} className="font-medium text-brand-700 hover:underline">
                    {invoice.number ?? "Draft"}
                  </Link>
                  <p className="text-xs text-muted">{invoice.subscription ? "Subscription" : "One-off"}</p>
                </td>
                <td className="px-4 py-3">{invoice.customer_name ?? invoice.customer_email ?? invoice.customer}</td>
                <td className="px-4 py-3">
                  <InvoiceStatusBadge status={invoice.status} />
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(invoice.total, invoice.currency)}</td>
                <td className="px-4 py-3 text-muted">{new Date(invoice.created_at).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.isPending && <p className="p-6 text-sm text-muted">Loading…</p>}
        {query.isError && (
          <p className="p-6 text-sm text-danger" role="alert">
            Could not load your invoices.
          </p>
        )}
        {!query.isPending && !query.isError && invoices.length === 0 && (
          <div className="p-10 text-center">
            <p className="font-medium">{status ? "No invoices with this status" : "No invoices yet"}</p>
          </div>
        )}
      </Card>
      {query.hasNextPage && (
        <div className="text-center">
          <Button variant="secondary" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>
            Load more
          </Button>
        </div>
      )}
    </div>
  );
}
