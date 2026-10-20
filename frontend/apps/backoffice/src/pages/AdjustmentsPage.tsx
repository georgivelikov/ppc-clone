import { Alert, Badge, Button, Card, Input, Label, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { ApiError, api, type Adjustment } from "../api";
import { useStaff } from "../auth";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong.");
const TONE = { pending: "warning", approved: "success", rejected: "neutral" } as const;

/** Corrections to a business's balance. One admin asks, another approves: nobody can pay themselves in two clicks. */
export function AdjustmentsPage() {
  const staff = useStaff();
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ["adjustments"], queryFn: () => api.adjustments() });
  const [merchant, setMerchant] = useState("");
  const [currency, setCurrency] = useState("EUR");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["adjustments"] });
  const request = useMutation({
    mutationFn: () => api.requestAdjustment({ merchant_id: merchant.trim(), currency, amount: Math.round(Number(amount) * 100), reason }),
    onSuccess: () => {
      setMerchant("");
      setAmount("");
      setReason("");
      refresh();
    },
    onError: (e) => setProblem(message(e)),
  });
  const decide = useMutation({
    mutationFn: (v: { id: string; approve: boolean }) => (v.approve ? api.approveAdjustment(v.id, "") : api.rejectAdjustment(v.id, "")),
    onSuccess: refresh,
    onError: (e) => setProblem(message(e)),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    request.mutate();
  };
  const admin = staff.role === "admin";
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-2xl font-semibold">Adjustments</h1>
      <p className="text-sm text-muted">A positive amount pays the business, a negative one takes it back. Nothing changes until a different admin approves.</p>
      {problem && <Alert>{problem}</Alert>}
      {admin ? (
        <Card>
          <h2 className="font-semibold">Ask for an adjustment</h2>
          <form onSubmit={submit} noValidate aria-label="New adjustment" className="mt-3 grid gap-3 sm:grid-cols-4">
            <div className="sm:col-span-2">
              <Label htmlFor="adj-merchant">Business id</Label>
              <Input id="adj-merchant" value={merchant} onChange={(e) => setMerchant(e.target.value)} placeholder="mer_…" />
            </div>
            <div>
              <Label htmlFor="adj-currency">Currency</Label>
              <select id="adj-currency" value={currency} onChange={(e) => setCurrency(e.target.value)} className="w-full rounded-md border border-border bg-surface px-2 py-2 text-sm">
                {["EUR", "BGN", "RON", "PLN", "HUF", "CZK", "GBP", "USD"].map((c) => <option key={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <Label htmlFor="adj-amount">Amount (−12.50 takes)</Label>
              <Input id="adj-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </div>
            <div className="sm:col-span-4">
              <Label htmlFor="adj-reason">Reason</Label>
              <Input id="adj-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="sm:col-span-4">
              <Button type="submit" loading={request.isPending}>Ask for approval</Button>
            </div>
          </form>
        </Card>
      ) : (
        <Alert tone="info" role="status">Only admins can ask for or approve adjustments.</Alert>
      )}
      <Card>
        <h2 className="font-semibold">All adjustments</h2>
        <ul className="mt-3 divide-y divide-border" aria-label="Adjustments">
          {list.data?.map((a: Adjustment) => (
            <li key={a.id} className="flex flex-wrap items-center justify-between gap-3 py-3" data-testid="adjustment-row">
              <div className="text-sm">
                <p className="font-medium">
                  {formatMoney(a.amount, a.currency)} <span className="text-muted">· {a.merchant_id}</span>
                </p>
                <p className="text-xs text-muted">
                  {a.reason} · asked by {a.requested_by}{a.decided_by ? ` · decided by ${a.decided_by}` : ""}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Badge tone={TONE[a.status]} data-testid="adjustment-status">{a.status}</Badge>
                {admin && a.status === "pending" && (
                  a.requested_by.toLowerCase() === staff.email.toLowerCase() ? (
                    <span className="text-xs text-muted">waiting for a second admin</span>
                  ) : (
                    <>
                      <Button size="sm" onClick={() => decide.mutate({ id: a.id, approve: true })}>Approve</Button>
                      <Button size="sm" variant="secondary" onClick={() => decide.mutate({ id: a.id, approve: false })}>Reject</Button>
                    </>
                  )
                )}
              </div>
            </li>
          ))}
        </ul>
        {list.isSuccess && list.data.length === 0 && <p className="py-3 text-sm text-muted">None yet.</p>}
      </Card>
    </div>
  );
}
