import { ApiError, type PayoutPreview } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { Dialog } from "../components/Dialog";
import { PayoutStatusBadge } from "../components/PayoutStatusBadge";
import { useTestMode } from "../layout/testMode";

const PAGE_SIZE = 20;

/** Shows what an instant payout costs and asks for confirmation before any money moves. */
function PayOutNowDialog({ preview, livemode, onClose }: { preview: PayoutPreview; livemode: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const mutation = useMutation({
    mutationFn: () => api.createPayout(preview.currency, livemode),
    onSuccess: async (payout) => {
      await Promise.all(
        ["payouts", "payout-preview", "balance"].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
      );
      onClose();
      void navigate(`/payouts/${payout.id}`);
    },
  });

  return (
    <Dialog title="Pay out now" onClose={onClose}>
      <div className="space-y-4">
        <dl className="divide-y divide-border text-sm">
          <div className="flex justify-between py-2">
            <dt className="text-muted">Available</dt>
            <dd className="font-medium">{formatMoney(preview.available, preview.currency)}</dd>
          </div>
          <div className="flex justify-between py-2">
            <dt className="text-muted">Instant payout fee (1%)</dt>
            <dd className="font-medium">−{formatMoney(preview.fee, preview.currency)}</dd>
          </div>
          <div className="flex justify-between py-2">
            <dt className="text-muted">You receive</dt>
            <dd className="font-semibold" data-testid="payout-net">
              {formatMoney(preview.net, preview.currency)}
            </dd>
          </div>
        </dl>
        <p className="text-sm text-muted">Waiting for the next scheduled payout is free.</p>
        {mutation.isError && (
          <Alert>{mutation.error instanceof ApiError ? mutation.error.message : "Could not create the payout."}</Alert>
        )}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" loading={mutation.isPending} onClick={() => mutation.mutate()}>
            Confirm payout
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

export function PayoutsPage() {
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const currency = merchant.default_currency;
  const [confirming, setConfirming] = useState(false);

  const preview = useQuery({
    queryKey: ["payout-preview", livemode, currency],
    queryFn: () => api.payoutPreview(currency, livemode),
  });
  const payouts = useInfiniteQuery({
    queryKey: ["payouts", { livemode }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listPayouts({ starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const rows = payouts.data?.pages.flatMap((page) => page.data) ?? [];

  const p = preview.data;
  const canPayOut = !!p && p.has_bank_account && p.net > 0;

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Payouts</h1>
        <Button onClick={() => setConfirming(true)} disabled={!canPayOut}>
          Pay out now
        </Button>
      </div>

      {p && !p.has_bank_account && (
        <Alert>
          Add a bank account to receive payouts.{" "}
          <Link to="/settings/payouts" className="font-medium underline">
            Go to payout settings
          </Link>
        </Alert>
      )}
      {p && p.has_bank_account && (
        <Card>
          <p className="text-sm text-muted">Ready to pay out</p>
          <p className="mt-1 text-2xl font-semibold" data-testid="payable-amount">
            {formatMoney(Math.max(p.available, 0), p.currency)}
          </p>
          <p className="mt-1 text-xs text-muted">
            {p.available > 0
              ? "Goes to your bank account automatically on your payout schedule."
              : "Nothing available yet. Payments become available after the settlement delay."}
          </p>
        </Card>
      )}

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Amount</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Expected arrival</th>
              <th className="px-4 py-3 font-medium">Created</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((payout) => (
              <tr key={payout.id} className="border-b border-border last:border-0 hover:bg-canvas">
                <td className="px-4 py-3 font-medium">
                  <Link to={`/payouts/${payout.id}`} className="hover:underline">
                    {formatMoney(payout.amount, payout.currency)}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <PayoutStatusBadge status={payout.status} />
                </td>
                <td className="px-4 py-3 text-muted">{payout.kind === "instant" ? "Instant" : "Scheduled"}</td>
                <td className="px-4 py-3 whitespace-nowrap text-muted">
                  {new Date(`${payout.arrival_date}T00:00:00`).toLocaleDateString()}
                </td>
                <td className="px-4 py-3 whitespace-nowrap text-muted">{new Date(payout.created).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {payouts.isPending && <p className="px-4 py-6 text-sm text-muted">Loading payouts…</p>}
        {payouts.isError && <p className="px-4 py-6 text-sm text-danger">Could not load payouts.</p>}
        {payouts.isSuccess && rows.length === 0 && (
          <p className="px-4 py-10 text-center text-sm text-muted">No payouts yet.</p>
        )}
      </Card>

      {payouts.hasNextPage && (
        <div className="text-center">
          <Button variant="secondary" loading={payouts.isFetchingNextPage} onClick={() => void payouts.fetchNextPage()}>
            Load more
          </Button>
        </div>
      )}

      {confirming && p && <PayOutNowDialog preview={p} livemode={livemode} onClose={() => setConfirming(false)} />}
    </div>
  );
}
