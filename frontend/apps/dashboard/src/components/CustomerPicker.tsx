import type { Customer } from "@cutpay/api-client";
import { Input } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { useTestMode } from "../layout/testMode";

/** Search for a customer by name or email and pick one. Calls `onChange` with the customer, or null when cleared. */
export function CustomerPicker({
  id,
  value,
  onChange,
}: {
  id: string;
  value: Customer | null;
  onChange: (customer: Customer | null) => void;
}) {
  const { testMode } = useTestMode();
  const [q, setQ] = useState("");
  const results = useQuery({
    queryKey: ["customers", { picker: true, q, livemode: !testMode }],
    queryFn: () => api.listCustomers({ q, limit: 8, livemode: !testMode }),
    enabled: value === null,
  });

  if (value) {
    return (
      <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-sm" data-testid="picked-customer">
        <span>
          <span className="font-medium">{value.name ?? value.email ?? value.id}</span>
          {value.email && value.name && <span className="ml-2 text-muted">{value.email}</span>}
        </span>
        <button type="button" className="text-brand-600 hover:underline" onClick={() => onChange(null)}>
          Change
        </button>
      </div>
    );
  }

  return (
    <div>
      <Input id={id} placeholder="Search customers" value={q} onChange={(e) => setQ(e.target.value)} autoComplete="off" />
      <ul className="mt-1 max-h-48 divide-y divide-border overflow-auto rounded-lg border border-border" aria-label="Customers">
        {(results.data?.data ?? []).map((customer) => (
          <li key={customer.id}>
            <button
              type="button"
              className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-canvas"
              onClick={() => onChange(customer)}
            >
              <span className="font-medium">{customer.name ?? customer.email ?? customer.id}</span>
              <span className="text-muted">{customer.default_payment_method ? "card saved" : "no card"}</span>
            </button>
          </li>
        ))}
        {results.isSuccess && results.data.data.length === 0 && <li className="px-3 py-2 text-sm text-muted">No customers found.</li>}
      </ul>
    </div>
  );
}
