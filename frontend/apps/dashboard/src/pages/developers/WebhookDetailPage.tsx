import { ApiError, type WebhookDelivery, type WebhookEndpoint } from "@cutpay/api-client";
import { Alert, Badge, Button, Card } from "@cutpay/ui";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { Dialog } from "../../components/Dialog";
import { EventTypePicker } from "../../components/EventTypePicker";
import { describeEvents } from "../../lib/eventTypes";

const PAGE_SIZE = 20;

const DELIVERY_TONE: Record<WebhookDelivery["status"], "success" | "warning" | "danger"> = {
  succeeded: "success",
  pending: "warning",
  failed: "danger",
};

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "—");

function DeliveryDialog({ deliveryId, canResend, onClose }: { deliveryId: string; canResend: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const delivery = useQuery({ queryKey: ["webhook-delivery", deliveryId], queryFn: () => api.getWebhookDelivery(deliveryId) });
  const resend = useMutation({
    mutationFn: () => api.retryWebhookDelivery(deliveryId),
    onSuccess: async () => {
      await Promise.all(
        ["webhook-delivery", "webhook-deliveries"].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
      );
    },
  });
  const d = delivery.data;

  return (
    <Dialog title="Delivery" onClose={onClose}>
      {!d ? (
        <p className="text-sm text-muted">{delivery.isError ? "Could not load the delivery." : "Loading…"}</p>
      ) : (
        <div className="space-y-4">
          <dl className="divide-y divide-border text-sm">
            <div className="flex justify-between gap-4 py-2">
              <dt className="text-muted">Event</dt>
              <dd className="font-mono text-xs">{d.event_type}</dd>
            </div>
            <div className="flex justify-between gap-4 py-2">
              <dt className="text-muted">Status</dt>
              <dd>
                <Badge tone={DELIVERY_TONE[d.status]}>{d.status}</Badge>
              </dd>
            </div>
            {d.status === "pending" && (
              <div className="flex justify-between gap-4 py-2">
                <dt className="text-muted">Next attempt</dt>
                <dd>{when(d.next_attempt_at)}</dd>
              </div>
            )}
          </dl>
          <div>
            <h3 className="mb-2 text-sm font-medium">Attempts</h3>
            {(d.attempt_log ?? []).length === 0 ? (
              <p className="text-sm text-muted">Not tried yet.</p>
            ) : (
              <ol className="space-y-2">
                {(d.attempt_log ?? []).map((attempt) => (
                  <li key={attempt.attempt} className="rounded-lg border border-border p-3 text-sm">
                    <p className="flex justify-between">
                      <span className="font-medium">
                        #{attempt.attempt} {attempt.response_code ? `HTTP ${attempt.response_code}` : "No response"}
                      </span>
                      <span className="text-xs text-muted">{attempt.duration_ms} ms</span>
                    </p>
                    <p className="text-xs text-muted">{when(attempt.created_at)}</p>
                    {attempt.error && <p className="mt-1 text-xs text-danger">{attempt.error}</p>}
                    {attempt.response_body_snippet && (
                      <pre className="mt-1 max-h-24 overflow-auto rounded bg-canvas p-2 text-xs whitespace-pre-wrap">{attempt.response_body_snippet}</pre>
                    )}
                  </li>
                ))}
              </ol>
            )}
          </div>
          {resend.isError && <Alert>{resend.error instanceof ApiError ? resend.error.message : "Could not resend."}</Alert>}
          <div className="flex justify-end gap-3">
            <Button type="button" variant="secondary" onClick={onClose}>
              Close
            </Button>
            {canResend && (
              <Button type="button" loading={resend.isPending} onClick={() => resend.mutate()}>
                Resend
              </Button>
            )}
          </div>
        </div>
      )}
    </Dialog>
  );
}

function EditEvents({ endpoint, onClose }: { endpoint: WebhookEndpoint; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [events, setEvents] = useState(endpoint.enabled_events);
  const save = useMutation({
    mutationFn: () => api.updateWebhookEndpoint(endpoint.id, { enabled_events: events }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["webhook-endpoint", endpoint.id] });
      onClose();
    },
  });
  return (
    <Dialog title="Events to send" onClose={onClose}>
      <div className="space-y-4">
        <EventTypePicker value={events} onChange={setEvents} />
        {save.isError && <Alert>{save.error instanceof ApiError ? save.error.message : "Could not save."}</Alert>}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" loading={save.isPending} disabled={events.length === 0} onClick={() => save.mutate()}>
            Save
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

export function WebhookDetailPage() {
  const { id = "" } = useParams();
  const { role } = useSession();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canManage = role === "owner" || role === "admin" || role === "developer";
  const [editing, setEditing] = useState(false);
  const [openDelivery, setOpenDelivery] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const endpoint = useQuery({ queryKey: ["webhook-endpoint", id], queryFn: () => api.getWebhookEndpoint(id) });
  const deliveries = useInfiniteQuery({
    queryKey: ["webhook-deliveries", id],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.listWebhookDeliveries({ endpoint: id, starting_after: pageParam, limit: PAGE_SIZE, livemode: endpoint.data?.livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
    enabled: !!endpoint.data,
    // Deliveries change on their own (retries), so keep the list fresh while the page is open.
    refetchInterval: 5000,
  });
  const rows = deliveries.data?.pages.flatMap((page) => page.data) ?? [];

  const toggle = useMutation({
    mutationFn: () => api.updateWebhookEndpoint(id, { status: endpoint.data?.status === "enabled" ? "disabled" : "enabled" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["webhook-endpoint", id] }),
  });
  const sendTest = useMutation({
    mutationFn: () => api.testWebhookEndpoint(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["webhook-deliveries", id] }),
  });
  const remove = useMutation({
    mutationFn: () => api.deleteWebhookEndpoint(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["webhook-endpoints"] });
      void navigate("/developers/webhooks");
    },
  });

  if (endpoint.isError) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Alert>We could not find that endpoint.</Alert>
        <Link to="/developers/webhooks" className="text-sm text-brand-700 hover:underline">
          Back to webhooks
        </Link>
      </div>
    );
  }
  const e = endpoint.data;
  if (!e) return <p className="text-sm text-muted">Loading…</p>;

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <Link to="/developers/webhooks" className="text-sm text-muted hover:text-ink">
          ← Webhooks
        </Link>
        <h1 className="mt-1 text-2xl font-semibold break-all">{e.url}</h1>
        <p className="mt-1 text-sm text-muted">{e.description ?? "No description"}</p>
      </div>

      {e.status === "disabled" && (
        <Alert>
          This endpoint is disabled and receives nothing. {e.disabled_reason}
          {canManage && " Enable it again once your server is fixed."}
        </Alert>
      )}

      <Card className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm text-muted">Status</p>
            <Badge tone={e.status === "enabled" ? "success" : "danger"}>{e.status === "enabled" ? "Enabled" : "Disabled"}</Badge>
          </div>
          {canManage && (
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" size="sm" onClick={() => sendTest.mutate()} loading={sendTest.isPending} disabled={e.status !== "enabled"}>
                Send test event
              </Button>
              <Button variant="secondary" size="sm" onClick={() => toggle.mutate()} loading={toggle.isPending}>
                {e.status === "enabled" ? "Disable" : "Enable"}
              </Button>
              <Button variant="secondary" size="sm" onClick={() => setConfirmDelete(true)}>
                Delete
              </Button>
            </div>
          )}
        </div>
        <div className="flex items-start justify-between gap-3 border-t border-border pt-3">
          <div>
            <p className="text-sm text-muted">Events</p>
            <p className="text-sm">{describeEvents(e.enabled_events)}</p>
          </div>
          {canManage && (
            <Button variant="ghost" size="sm" onClick={() => setEditing(true)}>
              Change
            </Button>
          )}
        </div>
        {sendTest.isSuccess && <p role="status" className="text-sm text-success">Test event queued. It appears below in a moment.</p>}
        {sendTest.isError && <Alert>{sendTest.error instanceof ApiError ? sendTest.error.message : "Could not send a test event."}</Alert>}
      </Card>

      <section aria-labelledby="deliveries-heading" className="space-y-3">
        <h2 id="deliveries-heading" className="text-lg font-semibold">
          Deliveries
        </h2>
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-muted uppercase">
              <tr>
                <th className="px-4 py-3 font-medium">Event</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Attempts</th>
                <th className="px-4 py-3 font-medium">Response</th>
                <th className="px-4 py-3 font-medium">Created</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((delivery) => (
                <tr key={delivery.id} className="border-b border-border last:border-0 hover:bg-canvas">
                  <td className="px-4 py-3">
                    <button type="button" className="font-mono text-xs hover:underline" onClick={() => setOpenDelivery(delivery.id)}>
                      {delivery.event_type}
                    </button>
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={DELIVERY_TONE[delivery.status]}>{delivery.status}</Badge>
                  </td>
                  <td className="px-4 py-3">{delivery.attempts}</td>
                  <td className="px-4 py-3 text-muted">{delivery.last_response_code ?? "—"}</td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted">{when(delivery.created_at)}</td>
                </tr>
              ))}
              {!deliveries.isPending && rows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-10 text-center text-muted">
                    Nothing sent to this endpoint yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </Card>
        {deliveries.hasNextPage && (
          <Button variant="secondary" onClick={() => void deliveries.fetchNextPage()} loading={deliveries.isFetchingNextPage}>
            Load more
          </Button>
        )}
      </section>

      {editing && <EditEvents endpoint={e} onClose={() => setEditing(false)} />}
      {openDelivery && <DeliveryDialog deliveryId={openDelivery} canResend={canManage} onClose={() => setOpenDelivery(null)} />}
      {confirmDelete && (
        <Dialog title="Delete endpoint" onClose={() => setConfirmDelete(false)}>
          <div className="space-y-4">
            <p className="text-sm">Events will no longer be sent to this URL. Past deliveries stay in your logs.</p>
            {remove.isError && <Alert>Could not delete the endpoint.</Alert>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setConfirmDelete(false)}>
                Cancel
              </Button>
              <Button type="button" loading={remove.isPending} onClick={() => remove.mutate()}>
                Delete endpoint
              </Button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  );
}
