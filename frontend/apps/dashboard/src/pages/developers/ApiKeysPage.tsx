import { ApiError, type ApiKey, type ApiKeyType } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { Dialog } from "../../components/Dialog";
import { RevealSecretDialog } from "../../components/RevealSecretDialog";
import { DevelopersTabs } from "../../layout/DevelopersTabs";
import { useTestMode } from "../../layout/testMode";

const SCOPE_RESOURCES = [
  ["payments", "Payments"],
  ["refunds", "Refunds"],
  ["balance", "Balance"],
  ["payouts", "Payouts"],
  ["bank_accounts", "Bank accounts"],
  ["webhooks", "Webhooks and events"],
] as const;

const TYPE_LABEL: Record<ApiKeyType, string> = {
  secret: "Secret",
  publishable: "Publishable",
  restricted: "Restricted",
};

const TYPE_HELP: Record<ApiKeyType, string> = {
  secret: "Full access. Keep it on your server and never put it in a web page or app.",
  publishable: "Safe for web pages. It can only start card payments with the checkout SDK.",
  restricted: "Access only to what you choose below. Good for a single integration.",
};

const STATUS_TONE: Record<ApiKey["status"], "success" | "warning" | "neutral" | "danger"> = {
  active: "success",
  expiring: "warning",
  expired: "neutral",
  revoked: "neutral",
};

const message = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback);

function CreateKeyDialog({ livemode, onClose, onCreated }: { livemode: boolean; onClose: () => void; onCreated: (key: ApiKey) => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState<ApiKeyType>("secret");
  const [scopes, setScopes] = useState<Record<string, "none" | "read" | "write">>({});

  const create = useMutation({
    mutationFn: () =>
      api.createApiKey({
        name: name.trim(),
        type,
        livemode,
        scopes:
          type === "restricted"
            ? Object.entries(scopes)
                .filter(([, level]) => level !== "none")
                .map(([resource, level]) => `${resource}:${level}`)
            : undefined,
      }),
    onSuccess: onCreated,
  });

  const restrictedWithoutScopes = type === "restricted" && !Object.values(scopes).some((level) => level !== "none");

  return (
    <Dialog title={`Create ${livemode ? "live" : "test"} API key`} onClose={onClose}>
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <div>
          <Label htmlFor="key-name">Name</Label>
          <Input id="key-name" value={name} maxLength={80} placeholder="Production server" onChange={(e) => setName(e.target.value)} required />
        </div>
        <fieldset>
          <legend className="mb-1.5 text-sm font-medium">Type</legend>
          <div className="space-y-2">
            {(Object.keys(TYPE_LABEL) as ApiKeyType[]).map((option) => (
              <label key={option} className="flex cursor-pointer items-start gap-2 text-sm">
                <input type="radio" name="key-type" className="mt-1" checked={type === option} onChange={() => setType(option)} />
                <span>
                  <span className="font-medium">{TYPE_LABEL[option]}</span>
                  <span className="block text-xs text-muted">{TYPE_HELP[option]}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        {type === "restricted" && (
          <fieldset className="rounded-lg border border-border p-3">
            <legend className="px-1 text-sm font-medium">Permissions</legend>
            <ul className="space-y-2">
              {SCOPE_RESOURCES.map(([resource, label]) => (
                <li key={resource} className="flex items-center justify-between gap-3 text-sm">
                  <label htmlFor={`scope-${resource}`}>{label}</label>
                  <select
                    id={`scope-${resource}`}
                    className="h-8 rounded-lg border border-border bg-surface px-2 text-sm"
                    value={scopes[resource] ?? "none"}
                    onChange={(e) => setScopes((current) => ({ ...current, [resource]: e.target.value as "none" | "read" | "write" }))}
                  >
                    <option value="none">None</option>
                    <option value="read">Read</option>
                    <option value="write">Write</option>
                  </select>
                </li>
              ))}
            </ul>
          </fieldset>
        )}
        {create.isError && <Alert>{message(create.error, "Could not create the key.")}</Alert>}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending} disabled={!name.trim() || restrictedWithoutScopes}>
            Create key
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

const GRACE_OPTIONS = [
  { hours: 0, label: "Stop the old key immediately" },
  { hours: 1, label: "Keep the old key for 1 hour" },
  { hours: 24, label: "Keep the old key for 24 hours" },
  { hours: 168, label: "Keep the old key for 7 days" },
];

function RollKeyDialog({ apiKey, onClose, onRolled }: { apiKey: ApiKey; onClose: () => void; onRolled: (key: ApiKey) => void }) {
  const [hours, setHours] = useState(0);
  const roll = useMutation({ mutationFn: () => api.rollApiKey(apiKey.id, hours), onSuccess: onRolled });
  return (
    <Dialog title={`Roll ${apiKey.name}`} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-muted">
          A new key is created with the same permissions. Update your server to use it before the old key stops working.
        </p>
        <fieldset className="space-y-2">
          <legend className="sr-only">When should the old key stop working?</legend>
          {GRACE_OPTIONS.map((option) => (
            <label key={option.hours} className="flex cursor-pointer items-center gap-2 text-sm">
              <input type="radio" name="grace" checked={hours === option.hours} onChange={() => setHours(option.hours)} />
              {option.label}
            </label>
          ))}
        </fieldset>
        {roll.isError && <Alert>{message(roll.error, "Could not roll the key.")}</Alert>}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" loading={roll.isPending} onClick={() => roll.mutate()}>
            Roll key
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

function RevokeKeyDialog({ apiKey, onClose }: { apiKey: ApiKey; onClose: () => void }) {
  const queryClient = useQueryClient();
  const revoke = useMutation({
    mutationFn: () => api.revokeApiKey(apiKey.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["api-keys"] });
      onClose();
    },
  });
  return (
    <Dialog title={`Revoke ${apiKey.name}`} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm">
          Anything using <code className="rounded bg-canvas px-1 text-xs">{apiKey.prefix}…{apiKey.last4}</code> will stop working
          within a minute. This cannot be undone.
        </p>
        {revoke.isError && <Alert>{message(revoke.error, "Could not revoke the key.")}</Alert>}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" loading={revoke.isPending} onClick={() => revoke.mutate()}>
            Revoke key
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

type Action = { kind: "create" } | { kind: "roll"; key: ApiKey } | { kind: "revoke"; key: ApiKey } | { kind: "reveal"; key: ApiKey; secret: string; title: string };

export function ApiKeysPage() {
  const { role } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const queryClient = useQueryClient();
  const [action, setAction] = useState<Action | null>(null);
  const canManage = role === "owner" || role === "admin" || role === "developer";

  const keys = useQuery({ queryKey: ["api-keys"], queryFn: () => api.listApiKeys() });
  const rows = (keys.data?.data ?? []).filter((key) => key.livemode === livemode);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["api-keys"] });

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Developers</h1>
        <Button onClick={() => setAction({ kind: "create" })} disabled={!canManage}>
          Create API key
        </Button>
      </div>
      <DevelopersTabs />
      <p className="text-sm text-muted">
        Showing <strong>{livemode ? "live" : "test"}</strong> keys. Use the switch at the top to see the other kind.
      </p>

      {keys.isError && <Alert>Could not load your API keys.</Alert>}
      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Name</th>
              <th className="px-4 py-3 font-medium">Key</th>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Last used</th>
              <th className="px-4 py-3 font-medium">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((key) => (
              <tr key={key.id} className="border-b border-border last:border-0">
                <td className="px-4 py-3 font-medium">{key.name}</td>
                <td className="px-4 py-3 font-mono text-xs">
                  {key.prefix}…{key.last4}
                </td>
                <td className="px-4 py-3">
                  {TYPE_LABEL[key.type]}
                  {key.type === "restricted" && <span className="block text-xs text-muted">{key.scopes.join(", ")}</span>}
                </td>
                <td className="px-4 py-3">
                  <Badge tone={STATUS_TONE[key.status]}>
                    {key.status === "expiring" && key.expires_at ? `Expires ${new Date(key.expires_at).toLocaleString()}` : key.status}
                  </Badge>
                </td>
                <td className="px-4 py-3 text-muted">{key.last_used_at ? new Date(key.last_used_at).toLocaleString() : "Never"}</td>
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  {canManage && (key.status === "active" || key.status === "expiring") && (
                    <>
                      <Button variant="ghost" size="sm" aria-label={`Roll ${key.name}`} onClick={() => setAction({ kind: "roll", key })}>
                        Roll
                      </Button>
                      <Button variant="ghost" size="sm" aria-label={`Revoke ${key.name}`} onClick={() => setAction({ kind: "revoke", key })}>
                        Revoke
                      </Button>
                    </>
                  )}
                </td>
              </tr>
            ))}
            {!keys.isPending && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center text-muted">
                  No {livemode ? "live" : "test"} API keys yet. Create one to start using the API.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Card>

      {action?.kind === "create" && (
        <CreateKeyDialog
          livemode={livemode}
          onClose={() => setAction(null)}
          onCreated={(key) => {
            void refresh();
            setAction({ kind: "reveal", key, secret: key.secret ?? "", title: "Your new API key" });
          }}
        />
      )}
      {action?.kind === "roll" && (
        <RollKeyDialog
          apiKey={action.key}
          onClose={() => setAction(null)}
          onRolled={(key) => {
            void refresh();
            setAction({ kind: "reveal", key, secret: key.secret ?? "", title: "Your replacement key" });
          }}
        />
      )}
      {action?.kind === "revoke" && <RevokeKeyDialog apiKey={action.key} onClose={() => setAction(null)} />}
      {action?.kind === "reveal" && (
        <RevealSecretDialog title={action.title} label={`${action.key.name} (${TYPE_LABEL[action.key.type].toLowerCase()})`} secret={action.secret} onClose={() => setAction(null)} />
      )}
    </div>
  );
}
