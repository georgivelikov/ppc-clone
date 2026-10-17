import type { PaymentStatus } from "@cutpay/api-client";
import { Button, Card, formatMoney, Input } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api } from "../api";
import { STATUS_LABEL, StatusBadge } from "../components/StatusBadge";
import { CreatePaymentDialog } from "./CreatePaymentDialog";

const PAGE_SIZE = 20;

/** Waits until typing pauses before searching, so each keystroke is not a request. */
function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export function PaymentsPage() {
  const [status, setStatus] = useState<PaymentStatus | "">("");
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const q = useDebounced(search.trim());

  const query = useInfiniteQuery({
    queryKey: ["payments", { status, q }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listPayments({ status, q, starting_after: pageParam, limit: PAGE_SIZE }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const payments = query.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Payments</h1>
        <Button onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden />
          Create test payment
        </Button>
      </div>

      <div className="flex flex-wrap gap-3">
        <Input
          type="search"
          aria-label="Search payments"
          placeholder="Search by ID or email"
          className="max-w-xs"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <select
          aria-label="Filter by status"
          className="h-10 rounded-lg border border-border bg-surface px-3 text-sm"
          value={status}
          onChange={(event) => setStatus(event.target.value as PaymentStatus | "")}
        >
          <option value="">All statuses</option>
          {Object.entries(STATUS_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Amount</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Description</th>
              <th className="px-4 py-3 font-medium">Customer</th>
              <th className="px-4 py-3 font-medium">Date</th>
            </tr>
          </thead>
          <tbody>
            {payments.map((payment) => (
              <tr key={payment.id} className="border-b border-border last:border-0 hover:bg-canvas">
                <td className="px-4 py-3 font-medium">
                  <Link to={`/payments/${payment.id}`} className="hover:underline">
                    {formatMoney(payment.amount, payment.currency)}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={payment.status} />
                </td>
                <td className="max-w-56 truncate px-4 py-3 text-muted">{payment.description ?? "—"}</td>
                <td className="px-4 py-3 text-muted">{payment.customer_email ?? "—"}</td>
                <td className="px-4 py-3 whitespace-nowrap text-muted">
                  {new Date(payment.created_at).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.isPending && <p className="px-4 py-6 text-sm text-muted">Loading payments…</p>}
        {query.isError && <p className="px-4 py-6 text-sm text-danger">Could not load payments.</p>}
        {query.isSuccess && payments.length === 0 && (
          <p className="px-4 py-10 text-center text-sm text-muted">
            {status || q ? "No payments match these filters." : "No payments yet. Create a test payment to get started."}
          </p>
        )}
      </Card>

      {query.hasNextPage && (
        <div className="text-center">
          <Button variant="secondary" loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
            Load more
          </Button>
        </div>
      )}

      {creating && <CreatePaymentDialog onClose={() => setCreating(false)} />}
    </div>
  );
}
