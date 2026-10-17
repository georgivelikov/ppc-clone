import { Badge, Button, Card, Input } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api";
import { DevelopersTabs } from "../../layout/DevelopersTabs";
import { useTestMode } from "../../layout/testMode";

const PAGE_SIZE = 50;

function statusTone(status: number): "success" | "warning" | "danger" | "neutral" {
  if (status >= 500) return "danger";
  if (status >= 400) return "warning";
  if (status >= 200 && status < 300) return "success";
  return "neutral";
}

export function LogsPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [status, setStatus] = useState("");
  const [method, setMethod] = useState("");
  const [path, setPath] = useState("");

  const logs = useInfiniteQuery({
    queryKey: ["request-logs", { livemode, status, method, path }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.listRequestLogs({
        status: status || undefined,
        method: method || undefined,
        path: path.trim() || undefined,
        starting_after: pageParam,
        limit: PAGE_SIZE,
        livemode,
      }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const rows = logs.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <h1 className="text-2xl font-semibold">Developers</h1>
      <DevelopersTabs />
      <p className="text-sm text-muted">
        The last 30 days of authenticated API calls to your <strong>{livemode ? "live" : "test"}</strong> account. Request and response
        bodies are never stored.
      </p>

      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">
          <span className="mb-1 block text-muted">Status</span>
          <select aria-label="Filter by status" value={status} onChange={(e) => setStatus(e.target.value)} className="h-10 rounded-lg border border-border bg-surface px-2 text-sm">
            <option value="">All</option>
            <option value="2xx">Success (2xx)</option>
            <option value="4xx">Client errors (4xx)</option>
            <option value="5xx">Server errors (5xx)</option>
          </select>
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-muted">Method</span>
          <select aria-label="Filter by method" value={method} onChange={(e) => setMethod(e.target.value)} className="h-10 rounded-lg border border-border bg-surface px-2 text-sm">
            <option value="">All</option>
            {["GET", "POST", "PATCH", "PUT", "DELETE"].map((option) => (
              <option key={option}>{option}</option>
            ))}
          </select>
        </label>
        <label className="min-w-48 flex-1 text-sm">
          <span className="mb-1 block text-muted">Path starts with</span>
          <Input aria-label="Filter by path" placeholder="/v1/payment_intents" value={path} onChange={(e) => setPath(e.target.value)} />
        </label>
      </div>

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Request</th>
              <th className="px-4 py-3 font-medium">Caller</th>
              <th className="px-4 py-3 font-medium">Time taken</th>
              <th className="px-4 py-3 font-medium">Time</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((entry) => (
              <tr key={entry.id} className="border-b border-border last:border-0 hover:bg-canvas">
                <td className="px-4 py-3">
                  <Badge tone={statusTone(entry.status)}>{entry.status}</Badge>
                </td>
                <td className="px-4 py-3 font-mono text-xs">
                  <span className="font-semibold">{entry.method}</span> {entry.path}
                  {entry.idempotency_key && <span className="block text-muted">Idempotency-Key: {entry.idempotency_key}</span>}
                </td>
                <td className="px-4 py-3 text-xs text-muted">
                  {entry.actor_type === "api_key" ? "API key" : "Dashboard"}
                  {entry.actor && <span className="block font-mono">{entry.actor}</span>}
                </td>
                <td className="px-4 py-3 text-muted">{entry.latency_ms} ms</td>
                <td className="px-4 py-3 whitespace-nowrap text-muted">{new Date(entry.created_at).toLocaleString()}</td>
              </tr>
            ))}
            {!logs.isPending && rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-10 text-center text-muted">
                  No requests match. Calls made with an API key or from this dashboard show up here.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Card>
      {logs.hasNextPage && (
        <Button variant="secondary" onClick={() => void logs.fetchNextPage()} loading={logs.isFetchingNextPage}>
          Load more
        </Button>
      )}
    </div>
  );
}
