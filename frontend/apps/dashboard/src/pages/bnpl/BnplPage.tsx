import { ApiError, type BnplApplicationStatus, type BnplProvider, type BnplSettings, type RoutingMode } from "@cutpay/api-client";
import { Alert, Button, Card, formatMoney, Input, Label } from "@cutpay/ui";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api } from "../../api";
import { BNPL_STATUS_LABEL, BnplStatusBadge, CircuitBadge } from "../../components/BnplBadges";
import { useTestMode } from "../../layout/testMode";
import { durationText, percentText, planLabel, ROUTING_MODES } from "../../lib/bnpl";

const PAGE_SIZE = 20;

/** How the provider is picked, which providers are on and in which order. Every change is saved at once. */
function RoutingSettings({ livemode }: { livemode: boolean }) {
  const queryClient = useQueryClient();
  const key = ["bnpl-settings", livemode];
  const settings = useQuery({ queryKey: key, queryFn: () => api.getBnplSettings(livemode) });
  const [weights, setWeights] = useState({ cost: "1", speed: "1", approval: "1" });
  const [problem, setProblem] = useState<string | null>(null);
  useEffect(() => {
    if (settings.data) {
      const w = settings.data.weights;
      setWeights({ cost: String(w.cost), speed: String(w.speed), approval: String(w.approval) });
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: (input: Parameters<typeof api.updateBnplSettings>[0]) => api.updateBnplSettings(input, livemode),
    onSuccess: (saved: BnplSettings) => {
      setProblem(null);
      queryClient.setQueryData(key, saved);
      // The order, the switches and the mode all change who is recommended and what is asked, so look again.
      void queryClient.invalidateQueries({ queryKey: ["bnpl-analytics"] });
    },
  });

  if (settings.isPending) return <Card aria-label="Routing">Loading…</Card>;
  if (settings.isError || !settings.data) {
    return (
      <Card aria-label="Routing">
        <Alert>Could not load the settings.</Alert>
      </Card>
    );
  }
  const current = settings.data;
  const mode = ROUTING_MODES.find((m) => m.value === current.routing_mode);

  const saveWeights = () => {
    const parsed = [weights.cost, weights.speed, weights.approval].map((value) => (value.trim() === "" ? NaN : Number(value)));
    if (parsed.some((n) => !Number.isInteger(n) || n < 0) || parsed.every((n) => n === 0)) {
      setProblem("Weights are whole numbers from 0 up, and at least one must be above 0.");
      return;
    }
    save.mutate({ weights: { cost: parsed[0]!, speed: parsed[1]!, approval: parsed[2]! } });
  };

  return (
    <Card aria-label="Routing">
      <h2 className="font-semibold">Routing</h2>
      <p className="mt-1 text-sm text-muted">Which providers your customers can use, and how Cutpay picks the best one for them.</p>

      <div className="mt-4 max-w-md">
        <Label htmlFor="routing-mode">How the provider is chosen</Label>
        <select
          id="routing-mode"
          value={current.routing_mode}
          onChange={(event) => save.mutate({ routing_mode: event.target.value as RoutingMode })}
          className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm"
        >
          {ROUTING_MODES.map((m) => (
            <option key={m.value} value={m.value}>
              {m.label}
            </option>
          ))}
        </select>
        {mode && <p className="mt-1 text-xs text-muted">{mode.help}</p>}
      </div>

      {current.routing_mode === "weighted" && (
        <fieldset className="mt-4 grid max-w-md grid-cols-3 gap-3">
          <legend className="mb-1 text-sm font-medium">Weights</legend>
          {(
            [
              ["cost", "Cost"],
              ["speed", "Speed"],
              ["approval", "Approval rate"],
            ] as const
          ).map(([field, label]) => (
            <div key={field}>
              <Label htmlFor={`weight-${field}`}>{label}</Label>
              <Input
                id={`weight-${field}`}
                type="number"
                min={0}
                step={1}
                value={weights[field]}
                onChange={(event) => setWeights({ ...weights, [field]: event.target.value })}
              />
            </div>
          ))}
          <div className="col-span-3">
            <Button type="button" variant="secondary" onClick={saveWeights} loading={save.isPending}>
              Save weights
            </Button>
          </div>
        </fieldset>
      )}

      <h3 className="mt-6 text-sm font-medium">Providers, in your order</h3>
      <ol className="mt-2 divide-y divide-border rounded-lg border border-border">
        {current.providers.map((provider, index) => (
          <ProviderRow
            key={provider.code}
            provider={provider}
            first={index === 0}
            last={index === current.providers.length - 1}
            busy={save.isPending}
            onToggle={(enabled) => save.mutate({ providers: [{ code: provider.code, enabled }] })}
            onMove={(by) => save.mutate({ providers: [{ code: provider.code, priority: index + by }] })}
          />
        ))}
      </ol>
      {problem && (
        <p role="alert" className="mt-3 text-sm text-danger">
          {problem}
        </p>
      )}
      {save.isError && <Alert className="mt-3">{save.error instanceof ApiError ? save.error.message : "Could not save."}</Alert>}
    </Card>
  );
}

function ProviderRow({
  provider,
  first,
  last,
  busy,
  onToggle,
  onMove,
}: {
  provider: BnplProvider;
  first: boolean;
  last: boolean;
  busy: boolean;
  onToggle: (enabled: boolean) => void;
  onMove: (by: -1 | 1) => void;
}) {
  const currency = provider.currencies[0] ?? "EUR";
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 px-4 py-3" data-testid={`provider-${provider.code}`}>
      <div>
        <p className="font-medium">{provider.name}</p>
        <p className="text-xs text-muted">
          {formatMoney(provider.min_amount, currency)} to {formatMoney(provider.max_amount, currency)} · {provider.countries.join(", ")}
        </p>
      </div>
      <div className="flex items-center gap-3">
        <label className="flex cursor-pointer items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={provider.enabled}
            disabled={busy}
            onChange={(event) => onToggle(event.target.checked)}
            aria-label={`Offer ${provider.name}`}
          />
          {provider.enabled ? "On" : "Off"}
        </label>
        <Button type="button" size="sm" variant="secondary" disabled={first || busy} onClick={() => onMove(-1)} aria-label={`Move ${provider.name} up`}>
          <ArrowUp className="size-4" aria-hidden />
        </Button>
        <Button type="button" size="sm" variant="secondary" disabled={last || busy} onClick={() => onMove(1)} aria-label={`Move ${provider.name} down`}>
          <ArrowDown className="size-4" aria-hidden />
        </Button>
      </div>
    </li>
  );
}

/** How each provider has done for this merchant, and whether the aggregator is sending customers to it right now. */
function Analytics({ livemode }: { livemode: boolean }) {
  const [days, setDays] = useState(30);
  const analytics = useQuery({ queryKey: ["bnpl-analytics", livemode, days], queryFn: () => api.getBnplAnalytics(days, livemode) });

  return (
    <Card className="overflow-x-auto p-0" aria-label="Providers">
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 pt-4">
        <div>
          <h2 className="font-semibold">Providers</h2>
          {analytics.data && (
            <p className="text-sm text-muted" data-testid="bnpl-totals">
              {analytics.data.applications} applications · {formatMoney(analytics.data.volume, "EUR")} financed ·{" "}
              {formatMoney(analytics.data.fees, "EUR")} in fees
            </p>
          )}
        </div>
        <select
          aria-label="Period"
          value={days}
          onChange={(event) => setDays(Number(event.target.value))}
          className="h-9 rounded-lg border border-border bg-surface px-3 text-sm"
        >
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
        </select>
      </div>
      <table className="mt-3 w-full text-left text-sm">
        <thead className="border-b border-border text-xs uppercase text-muted">
          <tr>
            <th className="px-4 py-3 font-medium">Provider</th>
            <th className="px-4 py-3 text-right font-medium">Applications</th>
            <th className="px-4 py-3 text-right font-medium">Approval rate</th>
            <th className="px-4 py-3 text-right font-medium">Usual wait</th>
            <th className="px-4 py-3 text-right font-medium">Financed</th>
            <th className="px-4 py-3 text-right font-medium">Fees</th>
            <th className="px-4 py-3 font-medium">Now</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {(analytics.data?.providers ?? []).map((row) => (
            <tr key={row.provider} data-testid={`analytics-${row.provider}`}>
              <td className="px-4 py-3 font-medium">{row.name}</td>
              <td className="px-4 py-3 text-right tabular-nums">
                {row.applications}
                {row.declined + row.failed > 0 && (
                  <span className="block text-xs text-muted">
                    {[row.declined > 0 && `${row.declined} declined`, row.failed > 0 && `${row.failed} not reached`].filter(Boolean).join(", ")}
                  </span>
                )}
              </td>
              <td className="px-4 py-3 text-right tabular-nums">{percentText(row.approval_rate)}</td>
              <td className="px-4 py-3 text-right tabular-nums">{durationText(row.p50_decision_ms)}</td>
              <td className="px-4 py-3 text-right tabular-nums">{formatMoney(row.volume, "EUR")}</td>
              <td className="px-4 py-3 text-right tabular-nums">{formatMoney(row.fees, "EUR")}</td>
              <td className="px-4 py-3">
                <CircuitBadge state={row.circuit} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {analytics.isPending && <p className="p-6 text-sm text-muted">Loading…</p>}
      {analytics.isError && (
        <p className="p-6 text-sm text-danger" role="alert">
          Could not load the figures.
        </p>
      )}
    </Card>
  );
}

function Applications({ livemode }: { livemode: boolean }) {
  const [status, setStatus] = useState<BnplApplicationStatus | "">("");
  const query = useInfiniteQuery({
    queryKey: ["bnpl-applications", { livemode, status }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.listBnplApplications({ status, starting_after: pageParam, limit: PAGE_SIZE, livemode }),
    getNextPageParam: (last) => (last.has_more ? last.data.at(-1)?.id : undefined),
  });
  const applications = query.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <section aria-label="Applications" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-semibold">Applications</h2>
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(event) => setStatus(event.target.value as BnplApplicationStatus | "")}
          className="h-10 rounded-lg border border-border bg-surface px-3 text-sm"
        >
          <option value="">All statuses</option>
          {Object.entries(BNPL_STATUS_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3 font-medium">Date</th>
              <th className="px-4 py-3 font-medium">Provider</th>
              <th className="px-4 py-3 font-medium">Plan</th>
              <th className="px-4 py-3 text-right font-medium">Amount</th>
              <th className="px-4 py-3 text-right font-medium">Fee</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 text-right font-medium">Decided in</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {applications.map((a) => (
              <tr key={a.id} className="hover:bg-canvas" data-testid="bnpl-application">
                <td className="px-4 py-3">
                  <Link to={`/payments/${a.payment}`} className="font-medium text-brand-700 hover:underline">
                    {new Date(a.created_at).toLocaleString()}
                  </Link>
                  <p className="text-xs text-muted">{a.customer_email ?? a.payment}</p>
                </td>
                <td className="px-4 py-3">{a.provider_name}</td>
                <td className="px-4 py-3">{planLabel(a)}</td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(a.amount, a.currency)}</td>
                <td className="px-4 py-3 text-right tabular-nums">{formatMoney(a.merchant_fee, a.currency)}</td>
                <td className="px-4 py-3">
                  <BnplStatusBadge status={a.status} />
                  {a.decline_reason && <p className="text-xs text-muted">{a.decline_reason.replaceAll("_", " ")}</p>}
                </td>
                <td className="px-4 py-3 text-right tabular-nums text-muted">{durationText(a.decision_ms ?? 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {query.isPending && <p className="p-6 text-sm text-muted">Loading…</p>}
        {query.isError && (
          <p className="p-6 text-sm text-danger" role="alert">
            Could not load the applications.
          </p>
        )}
        {!query.isPending && !query.isError && applications.length === 0 && (
          <div className="p-10 text-center">
            <p className="font-medium">{status ? "No applications with this status" : "No applications yet"}</p>
            {!status && <p className="mt-1 text-sm text-muted">They appear here when a customer chooses to pay in instalments at your checkout.</p>}
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
    </section>
  );
}

export function BnplPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Instalments</h1>
        <p className="mt-1 text-sm text-muted">
          Customers can pay in parts with buy-now-pay-later providers. You are paid the price less the provider&apos;s fee as soon as
          the provider approves them.
        </p>
      </div>
      <RoutingSettings livemode={livemode} />
      <Analytics livemode={livemode} />
      <Applications livemode={livemode} />
    </div>
  );
}
