import { Button, Card, formatMoney } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Plus, QrCode } from "lucide-react";
import { Link } from "react-router";
import { api } from "../../api";
import { LINK_TYPE_LABEL, LinkStatusBadge } from "../../components/LinkStatusBadge";
import { useTestMode } from "../../layout/testMode";

const PAGE_SIZE = 20;

export function LinksPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const query = useInfiniteQuery({
    queryKey: ["links", { livemode }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listPaymentLinks({ starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const links = query.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Payment links</h1>
          <p className="mt-1 text-sm text-muted">Get paid without a website: share a link or print a QR code.</p>
        </div>
        <div className="flex gap-2">
          <Link to="/terminal/qr">
            <Button variant="secondary">
              <QrCode className="size-4" aria-hidden />
              Charge with QR
            </Button>
          </Link>
          <Link to="/links/new">
            <Button>
              <Plus className="size-4" aria-hidden />
              Create link
            </Button>
          </Link>
        </div>
      </div>

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Link</th>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 text-right font-medium">Payments</th>
              <th className="px-4 py-3 text-right font-medium">Collected</th>
              <th className="px-4 py-3 font-medium">Created</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {links.map((link) => (
              <tr key={link.id} className="hover:bg-canvas">
                <td className="px-4 py-3">
                  <Link to={`/links/${link.id}`} className="font-medium text-brand-700 hover:underline">
                    {link.title}
                  </Link>
                  <p className="text-xs text-muted">{link.url.replace(/^https?:\/\//, "")}</p>
                </td>
                <td className="px-4 py-3">{LINK_TYPE_LABEL[link.type]}</td>
                <td className="px-4 py-3">
                  <LinkStatusBadge status={link.status} />
                </td>
                <td className="px-4 py-3 text-right tabular-nums">
                  {link.uses_count}
                  {link.max_uses !== null && <span className="text-muted"> / {link.max_uses}</span>}
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(link.amount_collected, link.currency)}</td>
                <td className="px-4 py-3 text-muted">{new Date(link.created_at).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.isPending && <p className="p-6 text-sm text-muted">Loading…</p>}
        {query.isError && (
          <p className="p-6 text-sm text-danger" role="alert">
            Could not load your links.
          </p>
        )}
        {!query.isPending && !query.isError && links.length === 0 && (
          <div className="p-10 text-center">
            <p className="font-medium">No payment links yet</p>
            <p className="mt-1 text-sm text-muted">Create one to start taking payments from a chat message or a printed QR code.</p>
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
    </div>
  );
}
