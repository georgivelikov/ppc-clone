import { Alert, Badge, Button, Card } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api, type DeadLetter } from "../api";
import { useStaff } from "../auth";

const money = (amount: number | null, currency: string | null) => (amount === null ? "none" : `${(amount / 100).toFixed(2)} ${currency ?? ""}`.trim());

/** The three accounts of the same money (payments, ledger, acquirer) and whether they agree. */
function Reconciliation() {
  const queryClient = useQueryClient();
  const runs = useQuery({ queryKey: ["recon-runs"], queryFn: () => api.reconciliationRuns(), refetchInterval: 10_000 });
  const [open, setOpen] = useState<string | null>(null);
  const mismatches = useQuery({
    queryKey: ["recon-mismatches", open],
    queryFn: () => api.reconciliationMismatches(open!),
    enabled: open !== null,
  });
  const run = useMutation({
    mutationFn: () => api.runReconciliation(),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ["recon-runs"] });
      if (result.mismatch_count > 0) setOpen(result.id);
    },
  });
  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">Reconciliation</h2>
          <p className="text-sm text-muted">Payments, the ledger and the acquirer&apos;s settlement file, compared. Runs every night; run it now to check today.</p>
        </div>
        <Button onClick={() => run.mutate()} loading={run.isPending}>
          Run now
        </Button>
      </div>
      {run.isError && <Alert>{run.error instanceof ApiError ? run.error.message : "The run failed."}</Alert>}
      <ul className="mt-3 divide-y divide-border" aria-label="Reconciliation runs">
        {runs.data?.map((r) => (
          <li key={r.id} className="py-3 text-sm" data-testid="recon-run">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p>
                {new Date(r.started_at).toLocaleString()} <span className="text-muted">· {r.scope_date ?? "everything"} · {r.payments_checked} payments, {r.refunds_checked} refunds, {r.acquirer_lines} acquirer lines</span>
              </p>
              <div className="flex items-center gap-2">
                <Badge tone={r.status === "clean" ? "success" : r.status === "running" ? "neutral" : "warning"}>
                  {r.status === "mismatched" ? `${r.mismatch_count} differences` : r.status}
                </Badge>
                {r.mismatch_count > 0 && (
                  <Button variant="ghost" size="sm" onClick={() => setOpen(open === r.id ? null : r.id)}>
                    {open === r.id ? "Hide" : "Show"}
                  </Button>
                )}
              </div>
            </div>
            {r.error && <p className="text-xs text-danger">{r.error}</p>}
            {open === r.id && (
              <table className="mt-2 w-full text-left text-xs" aria-label="Differences">
                <thead className="text-muted">
                  <tr>
                    <th className="py-1">What</th>
                    <th>Payment</th>
                    <th>Payments</th>
                    <th>Ledger</th>
                    <th>Acquirer</th>
                  </tr>
                </thead>
                <tbody>
                  {mismatches.data?.map((m) => (
                    <tr key={`${m.kind}-${m.payment_id}`} data-testid="recon-mismatch" title={m.detail}>
                      <td className="py-1 font-medium">{m.kind.replaceAll("_", " ")}</td>
                      <td>{m.payment_id}</td>
                      <td>{money(m.payments_amount, m.currency)}</td>
                      <td>{money(m.ledger_amount, m.currency)}</td>
                      <td>{money(m.acquirer_amount, m.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </li>
        ))}
      </ul>
      {runs.isSuccess && runs.data.length === 0 && <p className="py-3 text-sm text-muted">No runs yet.</p>}
    </Card>
  );
}

/** Records the services gave up on after their retries. */
function DeadLetters() {
  const staff = useStaff();
  const queryClient = useQueryClient();
  const letters = useQuery({ queryKey: ["dead-letters"], queryFn: () => api.deadLetters(), refetchInterval: 10_000 });
  const act = useMutation({
    mutationFn: async ({ letter, action }: { letter: DeadLetter; action: "redrive" | "discard" }) => {
      await (action === "redrive" ? api.redrive(letter) : api.discard(letter));
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["dead-letters"] }),
  });
  const isAdmin = staff.role === "admin";
  return (
    <Card>
      <h2 className="text-lg font-semibold">Dead letters</h2>
      <p className="text-sm text-muted">
        Events a service could not process after several tries. Send one back once the cause is fixed; services that already handled it skip it.
        {!isAdmin && " Only admins can act on them."}
      </p>
      {act.isError && <Alert>{act.error instanceof ApiError ? act.error.message : "That did not work."}</Alert>}
      <ul className="mt-3 divide-y divide-border" aria-label="Dead letters">
        {letters.data?.map((d) => (
          <li key={`${d.topic}-${d.partition}-${d.offset}`} className="space-y-1 py-3 text-sm" data-testid="dead-letter">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="font-medium">
                {d.event_type ?? "event"} <span className="text-muted">· {d.original_topic ?? d.topic} · {d.key}</span>
              </p>
              {isAdmin && (
                <div className="flex gap-2">
                  <Button size="sm" onClick={() => act.mutate({ letter: d, action: "redrive" })} disabled={act.isPending}>
                    Send back
                  </Button>
                  <Button size="sm" variant="secondary" onClick={() => act.mutate({ letter: d, action: "discard" })} disabled={act.isPending}>
                    Discard
                  </Button>
                </div>
              )}
            </div>
            <p className="text-xs text-danger">{d.error ?? "no reason recorded"}</p>
            <p className="text-xs text-muted">{new Date(d.at).toLocaleString()} · {d.topic} #{d.offset}</p>
          </li>
        ))}
      </ul>
      {letters.isSuccess && letters.data.length === 0 && <p className="py-3 text-sm text-muted">Nothing is stuck.</p>}
    </Card>
  );
}

export function OperationsPage() {
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-2xl font-semibold">Operations</h1>
      <Reconciliation />
      <DeadLetters />
    </div>
  );
}
