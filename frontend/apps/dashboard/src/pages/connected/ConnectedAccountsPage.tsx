import { ApiError, type ConnectedAccount } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, FieldError, formatMoney, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { api } from "../../api";
import { Dialog } from "../../components/Dialog";
import { useTestMode } from "../../layout/testMode";

function NewAccountDialog({ livemode, onClose }: { livemode: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [iban, setIban] = useState("");
  const [problems, setProblems] = useState<Record<string, string>>({});

  const create = useMutation({
    mutationFn: () => api.createConnectedAccount({ name: name.trim(), email: email.trim() || undefined, iban: iban.trim() }, livemode),
    onSuccess: async (account) => {
      await queryClient.invalidateQueries({ queryKey: ["connected-accounts"] });
      navigate(`/connected-accounts/${account.id}`);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (!name.trim()) found.name = "Give the account a name";
    if (!iban.trim()) found.iban = "Enter the IBAN it is paid out to";
    if (email.trim() && !/^\S+@\S+\.\S+$/.test(email.trim())) found.email = "Enter a valid email";
    setProblems(found);
    if (Object.keys(found).length === 0) create.mutate();
  }

  return (
    <Dialog title="New connected account" onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {create.isError && <Alert>{create.error instanceof ApiError ? create.error.message : "Could not create the account."}</Alert>}
        <div>
          <Label htmlFor="acct-name">Name</Label>
          <Input id="acct-name" autoFocus value={name} onChange={(e) => setName(e.target.value)} aria-invalid={Boolean(problems.name)} />
          <FieldError>{problems.name}</FieldError>
        </div>
        <div>
          <Label htmlFor="acct-email">Email (optional)</Label>
          <Input id="acct-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} aria-invalid={Boolean(problems.email)} />
          <FieldError>{problems.email}</FieldError>
        </div>
        <div>
          <Label htmlFor="acct-iban">IBAN</Label>
          <Input id="acct-iban" value={iban} onChange={(e) => setIban(e.target.value)} placeholder="BG80 BNBG 9661 1020 3456 78" aria-invalid={Boolean(problems.iban)} />
          <FieldError>{problems.iban}</FieldError>
          <p className="mt-1 text-xs text-muted">Payouts of this account go here, separately from yours.</p>
        </div>
        <Button type="submit" className="w-full" loading={create.isPending}>
          Create account
        </Button>
      </form>
    </Dialog>
  );
}

/** What a connected account is owed right now, from the ledger. */
export function AccountBalance({ account }: { account: ConnectedAccount }) {
  const balance = useQuery({
    queryKey: ["connected-account-balance", account.id],
    queryFn: () => api.getBalance(account.livemode, account.id),
    refetchInterval: 10_000,
  });
  const pending = balance.data?.pending.find((row) => row.currency === account.currency)?.amount ?? 0;
  const available = balance.data?.available.find((row) => row.currency === account.currency)?.amount ?? 0;
  return (
    <span className="tabular-nums" data-testid={`balance-${account.id}`}>
      {balance.isPending ? "…" : `${formatMoney(pending, account.currency)} pending · ${formatMoney(available, account.currency)} available`}
    </span>
  );
}

export function ConnectedAccountsPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [creating, setCreating] = useState(false);
  const accounts = useQuery({ queryKey: ["connected-accounts", { livemode }], queryFn: () => api.listConnectedAccounts({ limit: 100, livemode }) });

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Connected accounts</h1>
          <p className="mt-1 text-sm text-muted">
            The sellers, restaurants or drivers on your platform. Send them part of a payment and they are paid out to their own bank account.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden />
          New account
        </Button>
      </div>

      {accounts.isPending && <p className="text-sm text-muted">Loading…</p>}
      {accounts.isError && <Alert>Could not load your connected accounts.</Alert>}
      {accounts.isSuccess && accounts.data.data.length === 0 && (
        <Card className="p-10 text-center">
          <p className="font-medium">No connected accounts yet</p>
          <p className="mt-1 text-sm text-muted">Create one, then pick it when you create a payment to share the money.</p>
        </Card>
      )}
      <ul className="space-y-3" aria-label="Connected accounts">
        {(accounts.data?.data ?? []).map((account) => (
          <li key={account.id}>
            <Card className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <Link to={`/connected-accounts/${account.id}`} className="font-medium text-brand-700 hover:underline">
                  {account.name}
                </Link>
                {account.status === "disabled" && <Badge className="ml-2">Disabled</Badge>}
                <p className="text-xs text-muted">
                  {account.id} · {account.country} •• {account.iban_last4}
                </p>
              </div>
              <AccountBalance account={account} />
            </Card>
          </li>
        ))}
      </ul>
      {creating && <NewAccountDialog livemode={livemode} onClose={() => setCreating(false)} />}
    </div>
  );
}
