import { ApiError, type WebhookEndpoint } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, FieldError, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { Dialog } from "../../components/Dialog";
import { EventTypePicker } from "../../components/EventTypePicker";
import { RevealSecretDialog } from "../../components/RevealSecretDialog";
import { DevelopersTabs } from "../../layout/DevelopersTabs";
import { useTestMode } from "../../layout/testMode";
import { describeEvents } from "../../lib/eventTypes";

function AddEndpointDialog({ livemode, onClose, onCreated }: { livemode: boolean; onClose: () => void; onCreated: (endpoint: WebhookEndpoint) => void }) {
  const [url, setUrl] = useState("");
  const [description, setDescription] = useState("");
  const [events, setEvents] = useState<string[]>(["*"]);

  const create = useMutation({
    mutationFn: () => api.createWebhookEndpoint({ url: url.trim(), enabled_events: events, description: description.trim() || undefined }, livemode),
    onSuccess: onCreated,
  });
  const urlProblem = create.error instanceof ApiError && create.error.param === "url" ? create.error.message : undefined;

  return (
    <Dialog title="Add webhook endpoint" onClose={onClose}>
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <div>
          <Label htmlFor="endpoint-url">Endpoint URL</Label>
          <Input
            id="endpoint-url"
            type="url"
            placeholder="https://example.com/webhooks/cutpay"
            value={url}
            aria-invalid={!!urlProblem}
            onChange={(e) => setUrl(e.target.value)}
            required
          />
          <FieldError>{urlProblem}</FieldError>
          {livemode ? null : <p className="mt-1.5 text-xs text-muted">In test mode http and localhost are fine.</p>}
        </div>
        <div>
          <Label htmlFor="endpoint-description">Description (optional)</Label>
          <Input id="endpoint-description" value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <EventTypePicker value={events} onChange={setEvents} />
        {create.isError && !urlProblem && (
          <Alert>{create.error instanceof ApiError ? create.error.message : "Could not add the endpoint."}</Alert>
        )}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending} disabled={!url.trim() || events.length === 0}>
            Add endpoint
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

export function WebhooksPage() {
  const { role } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [revealed, setRevealed] = useState<WebhookEndpoint | null>(null);
  const canManage = role === "owner" || role === "admin" || role === "developer";

  const endpoints = useQuery({ queryKey: ["webhook-endpoints", livemode], queryFn: () => api.listWebhookEndpoints(livemode) });
  const rows = endpoints.data?.data ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Developers</h1>
        <Button onClick={() => setAdding(true)} disabled={!canManage}>
          Add endpoint
        </Button>
      </div>
      <DevelopersTabs />
      <p className="text-sm text-muted">
        Cutpay sends a signed HTTP request to each endpoint when something happens in your account. Showing{" "}
        <strong>{livemode ? "live" : "test"}</strong> endpoints.
      </p>

      {endpoints.isError && <Alert>Could not load your webhook endpoints.</Alert>}
      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">URL</th>
              <th className="px-4 py-3 font-medium">Events</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Added</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((endpoint) => (
              <tr key={endpoint.id} className="border-b border-border last:border-0 hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/developers/webhooks/${endpoint.id}`} className="font-medium break-all hover:underline">
                    {endpoint.url}
                  </Link>
                  {endpoint.description && <span className="block text-xs text-muted">{endpoint.description}</span>}
                </td>
                <td className="px-4 py-3 text-muted">{describeEvents(endpoint.enabled_events)}</td>
                <td className="px-4 py-3">
                  <Badge tone={endpoint.status === "enabled" ? "success" : "danger"}>
                    {endpoint.status === "enabled" ? "Enabled" : "Disabled"}
                  </Badge>
                </td>
                <td className="px-4 py-3 whitespace-nowrap text-muted">{new Date(endpoint.created_at).toLocaleDateString()}</td>
              </tr>
            ))}
            {!endpoints.isPending && rows.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-10 text-center text-muted">
                  No {livemode ? "live" : "test"} endpoints yet. Add one to start receiving events.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Card>

      {adding && (
        <AddEndpointDialog
          livemode={livemode}
          onClose={() => setAdding(false)}
          onCreated={(endpoint) => {
            void queryClient.invalidateQueries({ queryKey: ["webhook-endpoints"] });
            setAdding(false);
            setRevealed(endpoint);
          }}
        />
      )}
      {revealed?.secret && (
        <RevealSecretDialog
          title="Signing secret"
          label={`Use this to verify requests sent to ${revealed.url}`}
          secret={revealed.secret}
          onClose={() => setRevealed(null)}
        />
      )}
    </div>
  );
}
