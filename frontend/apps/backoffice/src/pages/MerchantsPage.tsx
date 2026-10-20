import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { ApiError, api } from "../api";
import { useStaff } from "../auth";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong.");

export const STATUS_TONE: Record<string, "neutral" | "success" | "warning" | "danger"> = {
  pending_kyc: "neutral",
  in_review: "warning",
  needs_more_info: "warning",
  active: "success",
  rejected: "danger",
  suspended: "danger",
};

export function MerchantsPage() {
  const [q, setQ] = useState("");
  const [submitted, setSubmitted] = useState("");
  const results = useQuery({ queryKey: ["merchants", submitted], queryFn: () => api.searchMerchants(submitted) });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(q);
  };
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-2xl font-semibold">Businesses</h1>
      <form onSubmit={submit} className="flex gap-2" role="search" aria-label="Find a business">
        <Input aria-label="Name, id or owner email" placeholder="Name, id or owner email" value={q} onChange={(e) => setQ(e.target.value)} />
        <Button type="submit">Search</Button>
      </form>
      {results.isError && <Alert>{message(results.error)}</Alert>}
      <Card>
        <ul className="divide-y divide-border" aria-label="Businesses">
          {results.data?.map((m) => (
            <li key={m.id} data-testid="merchant-row">
              <Link to={`/merchants/${m.id}`} className="flex flex-wrap items-center justify-between gap-3 py-3 hover:bg-canvas">
                <div>
                  <p className="text-sm font-medium">{m.display_name}</p>
                  <p className="text-xs text-muted">
                    {m.id} · {m.owner_email ?? "no owner"} · {m.country}
                  </p>
                </div>
                <Badge tone={STATUS_TONE[m.status] ?? "neutral"}>{m.status.replaceAll("_", " ")}</Badge>
              </Link>
            </li>
          ))}
        </ul>
        {results.isSuccess && results.data.length === 0 && <p className="py-3 text-sm text-muted">Nothing found.</p>}
      </Card>
    </div>
  );
}

export function MerchantPage() {
  const { id = "" } = useParams();
  const staff = useStaff();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ["merchant", id], queryFn: () => api.merchant(id) });
  const [flag, setFlag] = useState("watchlist");
  const [note, setNote] = useState("");
  const [holdReason, setHoldReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["merchant", id] });
  const onError = (e: unknown) => setProblem(message(e));
  const mark = useMutation({ mutationFn: () => api.setFlag(id, flag, note), onSuccess: () => { setNote(""); refresh(); }, onError });
  const clear = useMutation({ mutationFn: (f: string) => api.clearFlag(id, f), onSuccess: refresh, onError });
  const freeze = useMutation({ mutationFn: () => api.freeze(id, holdReason), onSuccess: () => { setHoldReason(""); refresh(); }, onError });
  const unfreeze = useMutation({ mutationFn: () => api.unfreeze(id), onSuccess: refresh, onError });

  if (detail.isError) return <Alert>{message(detail.error)}</Alert>;
  const d = detail.data;
  if (!d) return <p className="text-sm text-muted">Loading…</p>;
  const visibleFlags = d.flags.filter((f) => f.flag !== "payouts_frozen");
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/merchants" className="text-sm text-muted hover:text-ink">← Businesses</Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold" data-testid="merchant-title">{d.merchant.display_name}</h1>
          <p className="text-sm text-muted">{d.merchant.id} · {d.merchant.legal_name} · {d.merchant.country} · owner {d.merchant.owner_email}</p>
        </div>
        <Badge tone={STATUS_TONE[d.merchant.status] ?? "neutral"} data-testid="merchant-status">{d.merchant.status.replaceAll("_", " ")}</Badge>
      </div>
      {problem && <Alert>{problem}</Alert>}

      <Card>
        <h2 className="font-semibold">Application</h2>
        <p className="mt-1 text-sm text-muted">
          Risk {String(d.application.risk_score) || "-"}{d.application.manual_review ? " · needs a person" : ""} · {d.application.people} people
        </p>
        <Link to={`/review/${id}`} className="mt-2 inline-block text-sm font-medium text-brand-700 hover:underline">Open the application</Link>
      </Card>

      <Card>
        <h2 className="font-semibold">Payouts</h2>
        {d.payouts_frozen ? (
          <div className="mt-2 flex flex-wrap items-center gap-3" data-testid="frozen">
            <Badge tone="danger">On hold</Badge>
            <span className="text-sm text-muted">{d.flags.find((f) => f.flag === "payouts_frozen")?.note}</span>
            {staff.role === "admin" && <Button variant="secondary" loading={unfreeze.isPending} onClick={() => unfreeze.mutate()}>Release the hold</Button>}
          </div>
        ) : staff.role === "admin" ? (
          <form className="mt-2 flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); setProblem(null); freeze.mutate(); }} aria-label="Hold payouts">
            <div className="min-w-64 flex-1">
              <Label htmlFor="hold">Why are payouts held?</Label>
              <Input id="hold" value={holdReason} onChange={(e) => setHoldReason(e.target.value)} />
            </div>
            <Button type="submit" variant="secondary" loading={freeze.isPending}>Hold payouts</Button>
          </form>
        ) : (
          <p className="mt-2 text-sm text-muted">Payouts are normal. Only admins can hold them.</p>
        )}
      </Card>

      <Card>
        <h2 className="font-semibold">Marks</h2>
        <ul className="mt-2 flex flex-wrap gap-2" aria-label="Marks">
          {visibleFlags.map((f) => (
            <li key={f.flag} className="flex items-center gap-2 rounded-full border border-border px-3 py-1 text-sm" data-testid={`flag-${f.flag}`}>
              {f.flag.replaceAll("_", " ")}{f.note ? `: ${f.note}` : ""}
              <button type="button" aria-label={`Clear ${f.flag}`} className="text-muted hover:text-ink" onClick={() => clear.mutate(f.flag)}>×</button>
            </li>
          ))}
          {visibleFlags.length === 0 && <li className="text-sm text-muted">None.</li>}
        </ul>
        <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); setProblem(null); mark.mutate(); }} aria-label="Add a mark">
          <div>
            <Label htmlFor="flag">Mark</Label>
            <select id="flag" value={flag} onChange={(e) => setFlag(e.target.value)} className="rounded-md border border-border bg-surface px-2 py-2 text-sm">
              {["watchlist", "high_risk", "chargeback_risk", "under_investigation"].map((f) => <option key={f} value={f}>{f.replaceAll("_", " ")}</option>)}
            </select>
          </div>
          <div className="min-w-48 flex-1">
            <Label htmlFor="note">Note</Label>
            <Input id="note" value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <Button type="submit" variant="secondary" loading={mark.isPending}>Add mark</Button>
        </form>
      </Card>

      <Card>
        <h2 className="font-semibold">Recent activity</h2>
        <ul className="mt-2 divide-y divide-border text-sm" aria-label="Activity">
          {d.activity.map((a) => (
            <li key={a.id} className="py-2">
              <span className="font-medium">{a.action}</span> <span className="text-muted">· {a.actor} · {new Date(a.created_at).toLocaleString()}{a.detail !== "null" ? ` · ${a.detail}` : ""}</span>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
