import { ApiError, type DisputeFile } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useRef, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { saveBlob } from "../../lib/blob";
import { useTestMode } from "../../layout/testMode";
import { DisputeBadge } from "./DisputesPage";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong.");

const kilobytes = (bytes: number) => (bytes < 1024 ? `${bytes} B` : `${Math.round(bytes / 1024)} KB`);

/** One dispute: what was held, the deadline, and the form for the evidence. */
export function DisputeDetailPage() {
  const { id = "" } = useParams();
  const { role } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const queryClient = useQueryClient();
  const dispute = useQuery({ queryKey: ["dispute", id, livemode], queryFn: () => api.getDispute(id, livemode), refetchInterval: (q) => (q.state.data?.status === "under_review" ? 2_000 : false) });
  const [evidence, setEvidence] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["dispute", id] });
    void queryClient.invalidateQueries({ queryKey: ["disputes"] });
  };
  const canAnswer = ["owner", "admin", "support"].includes(role);

  const upload = useMutation({
    mutationFn: (file: File) => api.uploadDisputeFile(id, file, livemode),
    onSuccess: refresh,
    onError: (e) => setProblem(message(e)),
  });
  const send = useMutation({
    mutationFn: () => api.submitDisputeEvidence(id, evidence, livemode),
    onSuccess: refresh,
    onError: (e) => setProblem(message(e)),
  });
  const accept = useMutation({
    mutationFn: () => api.acceptDispute(id, livemode),
    onSuccess: refresh,
    onError: (e) => setProblem(message(e)),
  });

  if (dispute.isError) return <Alert>Could not load this dispute.</Alert>;
  const d = dispute.data;
  if (!d) return <p className="text-sm text-muted">Loading…</p>;
  const open = d.status === "needs_response";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    send.mutate();
  };

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link to="/disputes" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden /> Disputes
      </Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold" data-testid="dispute-amount">
            {formatMoney(d.amount, d.currency)}
          </h1>
          <p className="text-sm text-muted">
            Payment{" "}
            <Link to={`/payments/${d.payment_intent}`} className="text-brand-700 hover:underline">
              {d.payment_intent}
            </Link>{" "}
            · reason: {d.reason}
          </p>
        </div>
        <div data-testid="dispute-status">
          <DisputeBadge status={d.status} />
        </div>
      </div>

      <Card>
        <dl className="divide-y divide-border text-sm">
          <div className="flex justify-between py-2">
            <dt className="text-muted">Held while it is decided</dt>
            <dd>{formatMoney(d.amount, d.currency)}</dd>
          </div>
          <div className="flex justify-between py-2">
            <dt className="text-muted">Dispute fee</dt>
            <dd>{formatMoney(d.fee, d.currency)}</dd>
          </div>
          <div className="flex justify-between py-2">
            <dt className="text-muted">Send evidence by</dt>
            <dd>{new Date(d.evidence_due_by).toLocaleString()}</dd>
          </div>
          {d.outcome_reason && (
            <div className="flex justify-between gap-6 py-2">
              <dt className="text-muted">Decision</dt>
              <dd className="text-right" data-testid="dispute-outcome">
                {d.status === "won" ? "You won. The amount and the fee come back. " : "You lost. The customer keeps the money. "}
                <span className="text-muted">{d.outcome_reason}</span>
              </dd>
            </div>
          )}
        </dl>
      </Card>

      <Card>
        <h2 className="font-semibold">Evidence</h2>
        {problem && <Alert className="mt-3">{problem}</Alert>}
        {d.status === "under_review" && (
          <Alert tone="info" role="status" className="mt-3">
            Your evidence was sent. The card network is deciding; this page updates by itself.
          </Alert>
        )}
        <ul className="mt-3 divide-y divide-border" aria-label="Files">
          {d.files.map((f: DisputeFile) => (
            <li key={f.id} className="flex items-center justify-between gap-3 py-2 text-sm" data-testid="dispute-file">
              <span className="truncate">
                {f.filename} <span className="text-muted">({kilobytes(f.size)})</span>
              </span>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                onClick={async () => saveBlob(await api.downloadDisputeFile(id, f.id, livemode), f.filename)}
              >
                Download
              </Button>
            </li>
          ))}
        </ul>
        {open && canAnswer ? (
          <form onSubmit={submit} className="mt-4 space-y-4" aria-label="Evidence">
            <div>
              <label htmlFor="evidence" className="mb-1 block text-sm font-medium">
                What happened
              </label>
              <textarea
                id="evidence"
                rows={5}
                maxLength={5000}
                value={evidence}
                onChange={(e) => setEvidence(e.target.value)}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
                placeholder="When and how the customer received what they paid for, and any messages with them."
              />
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <input
                ref={fileInput}
                type="file"
                aria-label="Attach a file"
                className="text-sm"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) {
                    setProblem(null);
                    upload.mutate(file);
                  }
                  e.target.value = "";
                }}
              />
              <span className="text-xs text-muted">Up to 5 files of 2 MB each.</span>
            </div>
            <div className="flex flex-wrap gap-3">
              <Button type="submit" loading={send.isPending}>
                Send evidence
              </Button>
              <Button type="button" variant="secondary" loading={accept.isPending} onClick={() => accept.mutate()}>
                Accept the dispute
              </Button>
            </div>
          </form>
        ) : (
          d.evidence_text && (
            <p className="mt-4 rounded-md bg-canvas p-3 text-sm whitespace-pre-wrap" data-testid="dispute-evidence">
              {d.evidence_text}
            </p>
          )
        )}
        {open && !canAnswer && <p className="mt-3 text-sm text-muted">Only owners, admins and support can answer a dispute.</p>}
      </Card>
    </div>
  );
}
