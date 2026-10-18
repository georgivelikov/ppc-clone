import { ApiError } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, Input, Label } from "@cutpay/ui";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { api } from "../../api";
import { Dialog } from "../../components/Dialog";
import { useTestMode } from "../../layout/testMode";

const PAGE_SIZE = 20;

function NewCustomerDialog({ livemode, onClose }: { livemode: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () =>
      api.createCustomer(
        { name: name.trim() || undefined, email: email.trim() || undefined, phone: phone.trim() || undefined },
        livemode,
      ),
    onSuccess: async (customer) => {
      await queryClient.invalidateQueries({ queryKey: ["customers"] });
      navigate(`/customers/${customer.id}`);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (email.trim() && !/^\S+@\S+\.\S+$/.test(email.trim())) {
      setEmailError("Enter a valid email");
      return;
    }
    setEmailError(null);
    create.mutate();
  }

  return (
    <Dialog title="New customer" onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {create.isError && (
          <Alert>{create.error instanceof ApiError ? create.error.message : "Could not create the customer."}</Alert>
        )}
        <div>
          <Label htmlFor="customer-name">Name</Label>
          <Input id="customer-name" autoFocus value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="customer-email">Email</Label>
          <Input id="customer-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} aria-invalid={!!emailError} />
          <FieldError>{emailError}</FieldError>
        </div>
        <div>
          <Label htmlFor="customer-phone">Phone (optional)</Label>
          <Input id="customer-phone" value={phone} onChange={(e) => setPhone(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" loading={create.isPending}>
          Create customer
        </Button>
      </form>
    </Dialog>
  );
}

export function CustomersPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);
  const query = useInfiniteQuery({
    queryKey: ["customers", { livemode, q }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listCustomers({ q, starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const customers = query.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Customers</h1>
          <p className="mt-1 text-sm text-muted">People and companies you sell to, with the cards they saved.</p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden />
          New customer
        </Button>
      </div>

      <Input aria-label="Search customers" placeholder="Search by name, email or id" value={q} onChange={(e) => setQ(e.target.value)} className="max-w-sm" />

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Customer</th>
              <th className="px-4 py-3 font-medium">Email</th>
              <th className="px-4 py-3 font-medium">Saved card</th>
              <th className="px-4 py-3 font-medium">Created</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {customers.map((customer) => (
              <tr key={customer.id} className="hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/customers/${customer.id}`} className="font-medium text-brand-700 hover:underline">
                    {customer.name ?? customer.email ?? customer.id}
                  </Link>
                  <p className="text-xs text-muted">{customer.id}</p>
                </td>
                <td className="px-4 py-3">{customer.email ?? "—"}</td>
                <td className="px-4 py-3">{customer.default_payment_method ? "Yes" : "No"}</td>
                <td className="px-4 py-3 text-muted">{new Date(customer.created_at).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.isPending && <p className="p-6 text-sm text-muted">Loading…</p>}
        {query.isError && (
          <p className="p-6 text-sm text-danger" role="alert">
            Could not load your customers.
          </p>
        )}
        {!query.isPending && !query.isError && customers.length === 0 && (
          <div className="p-10 text-center">
            <p className="font-medium">{q ? "No customers match your search" : "No customers yet"}</p>
            {!q && <p className="mt-1 text-sm text-muted">Add a customer to save their card and start a subscription.</p>}
          </div>
        )}
      </Card>
      {query.hasNextPage && (
        <div className="text-center">
          <Button variant="secondary" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>
            Load more
          </Button>
        </div>
      )}
      {creating && <NewCustomerDialog livemode={livemode} onClose={() => setCreating(false)} />}
    </div>
  );
}
