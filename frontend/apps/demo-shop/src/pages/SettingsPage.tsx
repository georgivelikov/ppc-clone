import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { getConfig, saveSetup, ShopApiError } from "../api";

/**
 * Where a developer connects their Cutpay account. In a real shop these three values would be environment
 * variables on the server; this page just saves them in the backend's memory so the demo can be set up in
 * the browser. The secret key is sent to the backend once and is never shown again.
 */
export function SettingsPage() {
  const queryClient = useQueryClient();
  const { data: config } = useQuery({ queryKey: ["config"], queryFn: getConfig });
  const [secretKey, setSecretKey] = useState("");
  const [publishableKey, setPublishableKey] = useState("");
  const [webhookSecret, setWebhookSecret] = useState("");

  const save = useMutation({
    mutationFn: () =>
      saveSetup({
        secretKey: secretKey || undefined,
        publishableKey: publishableKey || undefined,
        webhookSecret: webhookSecret || undefined,
      }),
    onSuccess: async () => {
      setSecretKey("");
      setWebhookSecret("");
      await queryClient.invalidateQueries({ queryKey: ["config"] });
    },
  });

  if (!config) return <p className="text-sm text-muted">Loading…</p>;
  const failure = save.error instanceof ShopApiError ? save.error.message : null;

  return (
    <section aria-labelledby="settings-heading" className="max-w-xl">
      <h1 id="settings-heading" className="mb-2 text-2xl font-semibold">
        Connect Cutpay
      </h1>
      <p className="mb-6 text-sm text-muted">
        Status:{" "}
        <Badge tone={config.configured ? "success" : "warning"}>{config.configured ? "keys set" : "keys missing"}</Badge>{" "}
        <Badge tone={config.webhookConfigured ? "success" : "warning"}>
          {config.webhookConfigured ? "webhook secret set" : "webhook secret missing"}
        </Badge>
      </p>
      {!config.allowSetup ? (
        <Alert>Setup from the browser is switched off. Set the CUTPAY_* environment variables on the server instead.</Alert>
      ) : (
        <Card>
          <form
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault();
              save.mutate();
            }}
          >
            <div>
              <Label htmlFor="secret-key">Secret key</Label>
              <Input id="secret-key" type="password" autoComplete="off" placeholder="sk_test_…" value={secretKey} onChange={(e) => setSecretKey(e.target.value)} />
            </div>
            <div>
              <Label htmlFor="publishable-key">Publishable key</Label>
              <Input id="publishable-key" autoComplete="off" placeholder="pk_test_…" value={publishableKey} onChange={(e) => setPublishableKey(e.target.value)} />
            </div>
            <div>
              <Label htmlFor="webhook-secret">Webhook signing secret</Label>
              <Input id="webhook-secret" type="password" autoComplete="off" placeholder="whsec_…" value={webhookSecret} onChange={(e) => setWebhookSecret(e.target.value)} />
            </div>
            {failure && <Alert>{failure}</Alert>}
            {save.isSuccess && <p role="status" className="text-sm text-success">Saved.</p>}
            <Button type="submit" loading={save.isPending}>
              Save
            </Button>
          </form>
        </Card>
      )}
      <p className="mt-6 text-sm text-muted">
        Register this URL as a webhook endpoint in Cutpay (Developers, Webhooks):{" "}
        <code className="rounded bg-canvas px-1.5 py-0.5 text-xs">{config.webhookUrl}</code>
      </p>
    </section>
  );
}
