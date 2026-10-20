import { Badge, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { api } from "../api";

/** Every dispute of every business. Cutpay watches; the card network decides. */
export function DisputesPage() {
  const disputes = useQuery({ queryKey: ["staff-disputes"], queryFn: () => api.disputes(), refetchInterval: 5_000 });
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-2xl font-semibold">Disputes</h1>
      <Card>
        <ul className="divide-y divide-border" aria-label="Disputes">
          {disputes.data?.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-3" data-testid="dispute-row">
              <div className="text-sm">
                <p className="font-medium">
                  {formatMoney(d.amount, d.currency)} <span className="text-muted">· fee {formatMoney(d.fee, d.currency)}</span>
                </p>
                <p className="text-xs text-muted">
                  <Link to={`/merchants/${d.merchant_id}`} className="hover:underline">{d.merchant_id}</Link> · {d.payment_id} · answer by {new Date(d.evidence_due_by).toLocaleDateString()}
                </p>
              </div>
              <Badge tone={d.status === "won" ? "success" : d.status === "lost" ? "neutral" : "warning"}>{d.status.replaceAll("_", " ")}</Badge>
            </li>
          ))}
        </ul>
        {disputes.isSuccess && disputes.data.length === 0 && <p className="py-3 text-sm text-muted">No disputes.</p>}
      </Card>
    </div>
  );
}

/** What staff did, newest first. */
export function AuditPage() {
  const audit = useQuery({ queryKey: ["staff-audit"], queryFn: () => api.audit() });
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-2xl font-semibold">Audit</h1>
      <p className="text-sm text-muted">Everything done in the back-office. Each entry also shows in the business's own activity.</p>
      <Card>
        <ul className="divide-y divide-border" aria-label="Audit">
          {audit.data?.map((a) => (
            <li key={a.id} className="py-3 text-sm" data-testid="audit-row">
              <p className="font-medium">{a.action}</p>
              <p className="text-xs text-muted">
                {a.actor} · <Link to={`/merchants/${a.merchant_id}`} className="hover:underline">{a.merchant_id}</Link> · {new Date(a.created_at).toLocaleString()}
                {a.detail !== "null" ? ` · ${a.detail}` : ""}
              </p>
            </li>
          ))}
        </ul>
        {audit.isSuccess && audit.data.length === 0 && <p className="py-3 text-sm text-muted">Nothing yet.</p>}
      </Card>
    </div>
  );
}

const LINKS = [
  { name: "Kafka UI", href: "http://localhost:8090", about: "Topics, consumer groups and lag." },
  { name: "Mailpit", href: "http://localhost:8025", about: "Every email the platform sent." },
  { name: "Gateway health", href: "http://localhost:8080/actuator/health", about: "Is the edge up?" },
  { name: "Identity health", href: "http://localhost:8081/actuator/health", about: "Sign-in, teams, onboarding." },
  { name: "Payments health", href: "http://localhost:8082/actuator/health", about: "Payments, refunds, disputes." },
  { name: "Ledger health", href: "http://localhost:8084/actuator/health", about: "The books." },
  { name: "Payouts health", href: "http://localhost:8085/actuator/health", about: "Payouts to banks." },
  { name: "Notifications health", href: "http://localhost:8092/actuator/health", about: "Email delivery." },
  { name: "Reporting health", href: "http://localhost:8094/actuator/health", about: "The read model behind reports." },
];

export function SystemPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-2xl font-semibold">System</h1>
      <p className="text-sm text-muted">Where to look when something seems wrong. These open on this machine.</p>
      <Card>
        <ul className="divide-y divide-border" aria-label="Links">
          {LINKS.map((l) => (
            <li key={l.name} className="py-3">
              <a href={l.href} target="_blank" rel="noreferrer" className="text-sm font-medium text-brand-700 hover:underline">{l.name}</a>
              <p className="text-xs text-muted">{l.about}</p>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
