import { ApiError, type PaymentLink } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Download, FileText, Mail, MessageCircle } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../../api";
import { LINK_TYPE_LABEL, LinkStatusBadge } from "../../components/LinkStatusBadge";
import { saveBlob, useBlobUrl } from "../../lib/blob";

/** The deep links the sharing buttons open. Pure, so the exact addresses are testable. */
export function shareLinks(link: Pick<PaymentLink, "title" | "url">) {
  const message = `${link.title}: ${link.url}`;
  return {
    email: `mailto:?subject=${encodeURIComponent(link.title)}&body=${encodeURIComponent(`Pay here: ${link.url}`)}`,
    whatsapp: `https://wa.me/?text=${encodeURIComponent(message)}`,
    viber: `viber://forward?text=${encodeURIComponent(message)}`,
  };
}

function QrPanel({ link }: { link: PaymentLink }) {
  const queryClient = useQueryClient();
  const [fg, setFg] = useState(link.qr_style.fg ?? "#000000");
  const [bg, setBg] = useState(link.qr_style.bg ?? "#ffffff");
  const [downloadError, setDownloadError] = useState(false);
  const { url: preview, error } = useBlobUrl(() => api.paymentLinkQr(link.id, { format: "svg", fg, bg }), [link.id, fg, bg]);
  const dirty = fg !== (link.qr_style.fg ?? "#000000") || bg !== (link.qr_style.bg ?? "#ffffff");

  const save = useMutation({
    mutationFn: () => api.updatePaymentLink(link.id, { qr_style: { fg, bg } }),
    onSuccess: (updated) => queryClient.setQueryData(["link", link.id], updated),
  });

  async function download(kind: "svg" | "png" | "pdf") {
    setDownloadError(false);
    try {
      if (kind === "pdf") saveBlob(await api.paymentLinkCard(link.id), `${link.slug}-table-card.pdf`);
      else saveBlob(await api.paymentLinkQr(link.id, { format: kind, size: 1024, fg, bg }), `${link.slug}.${kind}`);
    } catch {
      setDownloadError(true);
    }
  }

  return (
    <Card className="space-y-4" aria-labelledby="qr-heading">
      <h2 id="qr-heading" className="font-semibold">
        QR code
      </h2>
      <div className="grid size-56 place-items-center rounded-lg border border-border bg-white" aria-live="polite">
        {preview ? (
          <img src={preview} alt={`QR code for ${link.title}`} className="size-full" />
        ) : (
          <span className="text-sm text-muted">{error ? "Could not draw the code" : "Drawing…"}</span>
        )}
      </div>
      <div className="flex gap-4 text-sm">
        <label className="flex items-center gap-2">
          Code
          <input type="color" value={fg} onChange={(e) => setFg(e.target.value)} aria-label="Code colour" className="h-8 w-10 cursor-pointer rounded border border-border" />
        </label>
        <label className="flex items-center gap-2">
          Background
          <input type="color" value={bg} onChange={(e) => setBg(e.target.value)} aria-label="Background colour" className="h-8 w-10 cursor-pointer rounded border border-border" />
        </label>
        {dirty && (
          <Button size="sm" variant="secondary" onClick={() => save.mutate()} loading={save.isPending}>
            Save colours
          </Button>
        )}
      </div>
      <p className="text-xs text-muted">Keep strong contrast between the two colours, or phones may not scan the code.</p>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" onClick={() => download("svg")}>
          <Download className="size-4" aria-hidden />
          SVG
        </Button>
        <Button size="sm" variant="secondary" onClick={() => download("png")}>
          <Download className="size-4" aria-hidden />
          PNG
        </Button>
        <Button size="sm" variant="secondary" onClick={() => download("pdf")}>
          <FileText className="size-4" aria-hidden />
          Table card (PDF)
        </Button>
      </div>
      {(downloadError || save.isError) && <Alert>Something went wrong. Please try again.</Alert>}
    </Card>
  );
}

export function LinkDetailPage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const [copied, setCopied] = useState(false);
  const link = useQuery({ queryKey: ["link", id], queryFn: () => api.getPaymentLink(id) });
  const payments = useQuery({ queryKey: ["link-payments", id], queryFn: () => api.paymentLinkPayments(id) });

  const toggle = useMutation({
    mutationFn: (active: boolean) => api.updatePaymentLink(id, { active }),
    onSuccess: (updated) => {
      queryClient.setQueryData(["link", id], updated);
      void queryClient.invalidateQueries({ queryKey: ["links"] });
    },
  });

  if (link.isPending) return <p className="text-sm text-muted">Loading…</p>;
  if (link.isError || !link.data) {
    const missing = link.error instanceof ApiError && link.error.status === 404;
    return <Alert>{missing ? "That payment link does not exist." : "Could not load the link."}</Alert>;
  }
  const l = link.data;
  const share = shareLinks(l);

  async function copy() {
    try {
      await navigator.clipboard.writeText(l.url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied; the address is shown on the page to copy by hand.
    }
  }

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <Link to="/links" className="text-sm text-brand-700 hover:underline">
          ← Payment links
        </Link>
        <div className="mt-1 flex flex-wrap items-center justify-between gap-3">
          <h1 className="flex items-center gap-3 text-2xl font-semibold">
            {l.title}
            <LinkStatusBadge status={l.status} />
          </h1>
          <Button variant="secondary" onClick={() => toggle.mutate(!l.active)} loading={toggle.isPending}>
            {l.active ? "Deactivate" : "Activate"}
          </Button>
        </div>
        {toggle.isError && <Alert className="mt-3">Could not change the link. Please try again.</Alert>}
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-6">
          <Card className="space-y-4">
            <h2 className="font-semibold">Share</h2>
            <div className="flex items-center gap-2 rounded-lg border border-border bg-canvas px-3 py-2">
              <code className="min-w-0 flex-1 truncate text-sm" data-testid="link-url">
                {l.url}
              </code>
              <Button size="sm" variant="secondary" onClick={copy}>
                <Copy className="size-4" aria-hidden />
                {copied ? "Copied" : "Copy"}
              </Button>
            </div>
            <div className="flex flex-wrap gap-2">
              <a href={share.email} className="inline-flex h-8 items-center gap-2 rounded-lg border border-border px-3 text-sm font-medium hover:bg-canvas">
                <Mail className="size-4" aria-hidden />
                Email
              </a>
              <a href={share.whatsapp} target="_blank" rel="noreferrer" className="inline-flex h-8 items-center gap-2 rounded-lg border border-border px-3 text-sm font-medium hover:bg-canvas">
                <MessageCircle className="size-4" aria-hidden />
                WhatsApp
              </a>
              <a href={share.viber} className="inline-flex h-8 items-center gap-2 rounded-lg border border-border px-3 text-sm font-medium hover:bg-canvas">
                <MessageCircle className="size-4" aria-hidden />
                Viber
              </a>
              <a href={l.url} target="_blank" rel="noreferrer" className="inline-flex h-8 items-center rounded-lg border border-border px-3 text-sm font-medium hover:bg-canvas">
                Open page
              </a>
            </div>
          </Card>

          <Card>
            <h2 className="mb-3 font-semibold">Details</h2>
            <dl className="grid grid-cols-[10rem_1fr] gap-y-2 text-sm">
              <dt className="text-muted">Type</dt>
              <dd>{LINK_TYPE_LABEL[l.type]}</dd>
              <dt className="text-muted">Price</dt>
              <dd>
                {l.type === "fixed" && l.amount !== null && formatMoney(l.amount, l.currency)}
                {l.type === "customer_chooses" && "Customer enters the amount"}
                {l.type === "products" && l.line_items.map((i) => `${i.product_name} × ${i.quantity}`).join(", ")}
              </dd>
              <dt className="text-muted">Payments</dt>
              <dd>
                {l.uses_count}
                {l.max_uses !== null && ` of ${l.max_uses}`}
              </dd>
              <dt className="text-muted">Collected</dt>
              <dd>{formatMoney(l.amount_collected, l.currency)}</dd>
              {l.expires_at && (
                <>
                  <dt className="text-muted">Expires</dt>
                  <dd>{new Date(l.expires_at).toLocaleString()}</dd>
                </>
              )}
              {l.collect.length > 0 && (
                <>
                  <dt className="text-muted">Asks for</dt>
                  <dd>{l.collect.join(", ")}</dd>
                </>
              )}
            </dl>
          </Card>

          <Card className="overflow-x-auto p-0">
            <h2 className="px-4 pt-4 font-semibold">Payments made with this link</h2>
            <table className="mt-2 w-full text-left text-sm">
              <thead className="border-b border-border text-xs uppercase text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">Payment</th>
                  <th className="px-4 py-2 font-medium">Date</th>
                  <th className="px-4 py-2 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {payments.data?.data.map((p) => (
                  <tr key={p.reservation_id}>
                    <td className="px-4 py-2">
                      <Link to={`/payments/${p.payment_id}`} className="text-brand-700 hover:underline">
                        {p.payment_id}
                      </Link>
                    </td>
                    <td className="px-4 py-2 text-muted">{new Date(p.created_at).toLocaleString()}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{formatMoney(p.amount, l.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {payments.data?.data.length === 0 && <p className="p-4 text-sm text-muted">Nobody has paid with this link yet.</p>}
          </Card>
        </div>

        <QrPanel link={l} />
      </div>
    </div>
  );
}
