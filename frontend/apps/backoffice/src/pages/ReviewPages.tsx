import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { ApiError, api } from "../api";
import { STATUS_TONE } from "./MerchantsPage";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong.");

export function ReviewQueuePage() {
  const [status, setStatus] = useState("in_review");
  const queue = useQuery({ queryKey: ["queue", status], queryFn: () => api.queue(status), refetchInterval: 5_000 });
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-2xl font-semibold">Review queue</h1>
      <p className="text-sm text-muted">Applications that wait for a person come first, then the oldest.</p>
      <label className="flex items-center gap-2 text-sm">
        Show
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded-md border border-border bg-surface px-2 py-1" aria-label="Status">
          <option value="in_review">In review</option>
          <option value="needs_more_info">Needs more information</option>
          <option value="rejected">Rejected</option>
          <option value="active">Approved</option>
        </select>
      </label>
      <Card>
        <ul className="divide-y divide-border" aria-label="Applications">
          {queue.data?.map((q) => (
            <li key={q.merchant_id} data-testid="queue-row">
              <Link to={`/review/${q.merchant_id}`} className="flex flex-wrap items-center justify-between gap-3 py-3 hover:bg-canvas">
                <div>
                  <p className="text-sm font-medium">{q.merchant_name}</p>
                  <p className="text-xs text-muted">
                    {q.merchant_id} · risk {q.risk_score ?? "-"} · submitted {new Date(q.submitted_at).toLocaleString()}
                  </p>
                </div>
                {q.manual_review ? <Badge tone="danger">needs a person</Badge> : <Badge tone="neutral">automatic</Badge>}
              </Link>
            </li>
          ))}
        </ul>
        {queue.isSuccess && queue.data.length === 0 && <p className="py-3 text-sm text-muted">Nothing waiting.</p>}
      </Card>
    </div>
  );
}

export function ReviewPage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const app = useQuery({ queryKey: ["application", id], queryFn: () => api.application(id) });
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const decide = useMutation({
    mutationFn: (decision: string) => api.decide(id, decision, reason),
    onSuccess: (updated) => {
      queryClient.setQueryData(["application", id], updated);
      void queryClient.invalidateQueries({ queryKey: ["queue"] });
    },
    onError: (e) => setProblem(message(e)),
  });
  const open = async (documentId: string, filename: string) => {
    const blob = await api.document(id, documentId);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  };

  if (app.isError) return <Alert>{message(app.error)}</Alert>;
  const a = app.data;
  if (!a) return <p className="text-sm text-muted">Loading…</p>;
  const company = a.data.company;
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/review" className="text-sm text-muted hover:text-ink">← Review queue</Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{company?.name ?? "Sole trader"}</h1>
          <p className="text-sm text-muted">
            <Link to={`/merchants/${id}`} className="hover:underline">{id}</Link> · {String(a.data.business_type)} · {String(a.data.industry)}
          </p>
        </div>
        <Badge tone={STATUS_TONE[a.status] ?? "neutral"} data-testid="application-status">{a.status.replaceAll("_", " ")}</Badge>
      </div>

      <Card>
        <h2 className="font-semibold">Risk</h2>
        <p className="mt-1 text-2xl font-semibold" data-testid="risk-score">{a.risk_score ?? "-"}</p>
        <ul className="mt-2 list-disc pl-5 text-sm text-muted">
          {a.risk_reasons.map((r) => <li key={r}>{r}</li>)}
        </ul>
        {company && <p className="mt-3 text-sm text-muted">Register: {company.name}, {company.address.line1}, {company.address.city} ({company.status})</p>}
      </Card>

      <Card>
        <h2 className="font-semibold">People</h2>
        <ul className="mt-2 divide-y divide-border" aria-label="People">
          {a.persons.map((p) => (
            <li key={p.id} className="py-3 text-sm" data-testid={`person-${p.name}`}>
              <p className="font-medium">{p.name} <span className="text-muted">· {p.role}{p.ownership_pct ? ` ${p.ownership_pct}%` : ""} · born {p.date_of_birth} · {p.nationality}</span></p>
              <p className="text-muted">
                {p.document_type} {p.document_number} · check: {p.verification_status.replaceAll("_", " ")}
                {p.verification_reason ? ` (${p.verification_reason})` : ""} · screening: {p.screening ?? "not run"}
              </p>
              <div className="mt-1 flex flex-wrap gap-2">
                {a.documents.filter((d) => d.person_id === p.id).map((d) => (
                  <Button key={d.id} size="sm" variant="secondary" onClick={() => void open(d.id, d.filename)}>
                    {d.type.replace("_", " ")}
                  </Button>
                ))}
              </div>
            </li>
          ))}
        </ul>
        {a.documents.filter((d) => d.person_id === null).map((d) => (
          <Button key={d.id} size="sm" variant="secondary" className="mt-2" onClick={() => void open(d.id, d.filename)}>
            {d.type.replace("_", " ")}
          </Button>
        ))}
      </Card>

      {a.decided_by && (
        <Alert tone="info" role="status" data-testid="decision">
          Decided by {a.decided_by}{a.decision_reason ? `: ${a.decision_reason}` : ""}
        </Alert>
      )}

      {a.status === "in_review" && (
        <Card>
          <h2 className="font-semibold">Decision</h2>
          {problem && <Alert className="mt-3">{problem}</Alert>}
          <div className="mt-3">
            <Label htmlFor="reason">Reason (sent to the business, required to reject or ask for more)</Label>
            <Input id="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          </div>
          <div className="mt-3 flex flex-wrap gap-3">
            <Button loading={decide.isPending} onClick={() => { setProblem(null); decide.mutate("approve"); }}>Approve</Button>
            <Button variant="secondary" onClick={() => { setProblem(null); decide.mutate("request_info"); }}>Ask for more information</Button>
            <Button variant="secondary" onClick={() => { setProblem(null); decide.mutate("reject"); }}>Reject</Button>
          </div>
        </Card>
      )}
    </div>
  );
}
