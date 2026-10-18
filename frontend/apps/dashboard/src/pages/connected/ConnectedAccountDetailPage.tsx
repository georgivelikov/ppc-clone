import { ApiError, type ConnectedAccount } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, FieldError, formatMoney, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api";
import { Dialog } from "../../components/Dialog";

function EditDialog({ account, onClose }: { account: ConnectedAccount; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(account.name);
  const [iban, setIban] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => api.updateConnectedAccount(account.id, { name: name.trim(), iban: iban.trim() || undefined }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["connected-account", account.id] });
      await queryClient.invalidateQueries({ queryKey: ["connected-accounts"] });
      onClose();
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) {
      setProblem("Give the account a name");
      return;
    }
    setProblem(null);
    save.mutate();
  }
  return (
    <Dialog title="Edit account" onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {save.isError && <Alert>{save.error instanceof ApiError ? save.error.message : "Could not save."}</Alert>}
        <div>
          <Label htmlFor="edit-name">Name</Label>
          <Input id="edit-name" value={name} onChange={(e) => setName(e.target.value)} aria-invalid={Boolean(problem)} />
          <FieldError>{problem}</FieldError>
        </div>
        <div>
          <Label htmlFor="edit-iban">New IBAN (optional)</Label>
          <Input id="edit-iban" value={iban} onChange={(e) => setIban(e.target.value)} placeholder={`${account.country} •• ${account.iban_last4}`} />
          <p className="mt-1 text-xs text-muted">Future payouts go to the new IBAN. It has to stay in {account.country}.</p>
        </div>
        <Button type="submit" className="w-full" loading={save.isPending}>
          Save
        </Button>
      </form>
    </Dialog>
  );
}

export function ConnectedAccountDetailPage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const account = useQuery({ queryKey: ["connected-account", id], queryFn: () => api.getConnectedAccount(id) });
  const a = account.data;
  const balance = useQuery({
    queryKey: ["connected-account-balance", id],
    queryFn: () => api.getBalance(a!.livemode, id),
    enabled: Boolean(a),
    refetchInterval: 10_000,
  });
  const transfers = useQuery({
    queryKey: ["connected-account-transfers", id],
    queryFn: () => api.listBalanceTransactions({ connected_account: id, limit: 20, livemode: a!.livemode }),
    enabled: Boolean(a),
    refetchInterval: 10_000,
  });
  const disable = useMutation({
    mutationFn: () => api.disableConnectedAccount(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["connected-account", id] });
      await queryClient.invalidateQueries({ queryKey: ["connected-accounts"] });
    },
  });

  if (account.isPending) return <p className="text-sm text-muted">Loading account…</p>;
  if (account.isError || !a) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Link to="/connected-accounts" className="text-sm text-brand-600 hover:underline">
          ← Connected accounts
        </Link>
        <Alert>{account.error instanceof ApiError && account.error.status === 404 ? "Account not found." : "Could not load the account."}</Alert>
      </div>
    );
  }
  const pending = balance.data?.pending.find((row) => row.currency === a.currency)?.amount ?? 0;
  const available = balance.data?.available.find((row) => row.currency === a.currency)?.amount ?? 0;
  const rows = (transfers.data?.data ?? []).filter((txn) => txn.type === "transfer" || txn.type === "transfer_reversal" || txn.type === "payout");

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/connected-accounts" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" aria-hidden />
        Connected accounts
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-3 text-2xl font-semibold">
            <span data-testid="account-name">{a.name}</span>
            {a.status === "disabled" && <Badge>Disabled</Badge>}
          </h1>
          <p className="mt-1 text-sm text-muted">
            {a.id} · {a.email ?? "no email"} · paid out to {a.country} •• {a.iban_last4} in {a.currency}
          </p>
        </div>
        {a.status === "active" && (
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => setEditing(true)}>
              Edit
            </Button>
            <Button variant="secondary" onClick={() => disable.mutate()} loading={disable.isPending}>
              Disable
            </Button>
          </div>
        )}
      </div>
      {disable.isError && <Alert>Could not disable the account.</Alert>}

      <div className="grid gap-4 sm:grid-cols-2">
        <Card>
          <p className="text-sm text-muted">Pending</p>
          <p className="mt-2 text-2xl font-semibold" data-testid="account-pending">
            {formatMoney(pending, a.currency)}
          </p>
          <p className="text-xs text-muted">Becomes available after the settlement delay</p>
        </Card>
        <Card>
          <p className="text-sm text-muted">Available</p>
          <p className="mt-2 text-2xl font-semibold" data-testid="account-available">
            {formatMoney(available, a.currency)}
          </p>
          <p className="text-xs text-muted">Paid out to the IBAN on the next payout run</p>
        </Card>
      </div>

      <Card className="overflow-x-auto p-0">
        <h2 className="px-4 pt-4 font-semibold">Activity</h2>
        <table className="mt-2 w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">What</th>
              <th className="px-4 py-3 font-medium">From</th>
              <th className="px-4 py-3 text-right font-medium">Amount</th>
              <th className="px-4 py-3 font-medium">Date</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border" data-testid="account-activity">
            {rows.map((txn) => (
              <tr key={txn.id}>
                <td className="px-4 py-3">{txn.type === "transfer" ? "Transfer" : txn.type === "payout" ? "Payout" : "Refund taken back"}</td>
                <td className="px-4 py-3 text-muted">{txn.parent_id ?? txn.source_id}</td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(txn.net, txn.currency)}</td>
                <td className="px-4 py-3 text-muted">{new Date(txn.created).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {transfers.isSuccess && rows.length === 0 && <p className="p-4 text-sm text-muted">Nothing yet. Create a payment with this account as a destination.</p>}
      </Card>

      {editing && <EditDialog account={a} onClose={() => setEditing(false)} />}
    </div>
  );
}
