import { Badge, Button, Card } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api";
import { Dialog } from "../../components/Dialog";
import { DevelopersTabs } from "../../layout/DevelopersTabs";
import { useTestMode } from "../../layout/testMode";
import { ALL_EVENT_TYPES } from "../../lib/eventTypes";

const PAGE_SIZE = 25;

export function EventsPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [type, setType] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);

  const events = useInfiniteQuery({
    queryKey: ["events", { livemode, type }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listEvents({ type: type || undefined, starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const rows = events.data?.pages.flatMap((page) => page.data) ?? [];
  const open = rows.find((event) => event.id === openId);

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <h1 className="text-2xl font-semibold">Developers</h1>
      <DevelopersTabs />
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted">
          Everything that happened in your <strong>{livemode ? "live" : "test"}</strong> account, exactly as sent to your endpoints.
        </p>
        <label className="flex items-center gap-2 text-sm">
          <span className="text-muted">Type</span>
          <select
            aria-label="Filter by event type"
            value={type}
            onChange={(e) => setType(e.target.value)}
            className="h-9 rounded-lg border border-border bg-surface px-2 text-sm"
          >
            <option value="">All types</option>
            {ALL_EVENT_TYPES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
      </div>

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Event ID</th>
              <th className="px-4 py-3 font-medium">Time</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((event) => (
              <tr key={event.id} className="border-b border-border last:border-0 hover:bg-canvas">
                <td className="px-4 py-3">
                  <button type="button" className="font-mono text-xs font-medium hover:underline" onClick={() => setOpenId(event.id)}>
                    {event.type}
                  </button>
                </td>
                <td className="px-4 py-3 font-mono text-xs text-muted">{event.id}</td>
                <td className="px-4 py-3 whitespace-nowrap text-muted">{new Date(event.created_at).toLocaleString()}</td>
              </tr>
            ))}
            {!events.isPending && rows.length === 0 && (
              <tr>
                <td colSpan={3} className="px-4 py-10 text-center text-muted">
                  No events yet. Make a test payment and it will show up here.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Card>
      {events.hasNextPage && (
        <Button variant="secondary" onClick={() => void events.fetchNextPage()} loading={events.isFetchingNextPage}>
          Load more
        </Button>
      )}

      {open && (
        <Dialog title={open.type} onClose={() => setOpenId(null)}>
          <div className="space-y-3">
            <p className="text-xs text-muted">
              <Badge>{open.livemode ? "live" : "test"}</Badge> {open.id}
            </p>
            <pre data-testid="event-json" className="max-h-80 overflow-auto rounded-lg bg-canvas p-3 text-xs">
              {JSON.stringify(open, null, 2)}
            </pre>
          </div>
        </Dialog>
      )}
    </div>
  );
}
