import { ApiError, type ReportFormat, type ReportRun, type ReportType } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api } from "../../api";
import { useTestMode } from "../../layout/testMode";
import { Insights } from "./Insights";

const isoDay = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

const STATUS_TONE: Record<ReportRun["status"], "neutral" | "success" | "warning" | "danger"> = {
  queued: "neutral",
  running: "warning",
  succeeded: "success",
  failed: "danger",
};

/** The numbers for the last weeks, and reports to download for your accountant. */
export function ReportsPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const queryClient = useQueryClient();
  const types = useQuery({ queryKey: ["report-types"], queryFn: () => api.listReportTypes() });
  const runs = useQuery({
    queryKey: ["report-runs", livemode],
    queryFn: () => api.listReportRuns(livemode),
    // While something is being built, look again soon.
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === "queued" || r.status === "running") ? 1_500 : false),
  });
  const [type, setType] = useState("monthly_statement");
  const [format, setFormat] = useState<ReportFormat>("csv");
  const [from, setFrom] = useState(isoDay(-30));
  const [to, setTo] = useState(isoDay(0));
  const [chosen, setChosen] = useState<string[] | null>(null);
  const selected: ReportType | undefined = types.data?.find((t) => t.id === type);
  const create = useMutation({
    mutationFn: () =>
      api.createReportRun({ type, format, interval_start: from, interval_end: to, columns: chosen && chosen.length ? chosen : undefined }, livemode),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["report-runs"] }),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <div className="mx-auto max-w-4xl space-y-8">
      <div>
        <h1 className="text-2xl font-semibold">Reports</h1>
        <p className="mt-1 text-sm text-muted">How the business is doing, and files to download. Numbers can be a few seconds behind the payments.</p>
      </div>

      <Insights />

      <Card>
        <h2 className="font-semibold">Make a report</h2>
        <form onSubmit={submit} noValidate aria-label="New report" className="mt-4 grid gap-4 sm:grid-cols-2">
          {create.isError && <Alert className="sm:col-span-2">{create.error instanceof ApiError ? create.error.message : "Could not start the report."}</Alert>}
          <div className="sm:col-span-2">
            <Label htmlFor="report-type">Report</Label>
            <select
              id="report-type"
              value={type}
              onChange={(e) => {
                setType(e.target.value);
                setChosen(null);
              }}
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            >
              {types.data?.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.title}
                </option>
              ))}
            </select>
            {selected && <p className="mt-1 text-xs text-muted">{selected.about}</p>}
          </div>
          <div>
            <Label htmlFor="report-from">From</Label>
            <Input id="report-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div>
            <Label htmlFor="report-to">To (included)</Label>
            <Input id="report-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div>
            <Label htmlFor="report-format">File</Label>
            <select id="report-format" value={format} onChange={(e) => setFormat(e.target.value as ReportFormat)} className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm">
              <option value="csv">CSV (opens in any spreadsheet)</option>
              <option value="xlsx">Excel (.xlsx)</option>
            </select>
          </div>
          {selected && (
            <fieldset className="sm:col-span-2">
              <legend className="text-sm font-medium">Columns</legend>
              <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-sm">
                {selected.columns.map((c) => {
                  const on = chosen === null || chosen.includes(c.key);
                  return (
                    <label key={c.key} className="flex items-center gap-1.5">
                      <input
                        type="checkbox"
                        checked={on}
                        onChange={(e) => {
                          const all = selected.columns.map((x) => x.key);
                          const current = chosen ?? all;
                          setChosen(e.target.checked ? all.filter((k) => k === c.key || current.includes(k)) : current.filter((k) => k !== c.key));
                        }}
                      />
                      {c.label}
                    </label>
                  );
                })}
              </div>
            </fieldset>
          )}
          <div className="sm:col-span-2">
            <Button type="submit" loading={create.isPending} disabled={chosen !== null && chosen.length === 0}>
              Make the report
            </Button>
          </div>
        </form>
      </Card>

      <Card>
        <h2 className="font-semibold">Your reports</h2>
        {runs.data?.length === 0 && <p className="mt-3 text-sm text-muted">None yet.</p>}
        <ul className="mt-3 divide-y divide-border" aria-label="Reports">
          {runs.data?.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-3" data-testid="report-run">
              <div>
                <p className="text-sm font-medium">
                  {types.data?.find((t) => t.id === r.type)?.title ?? r.type} <span className="text-muted">· {r.format.toUpperCase()}</span>
                </p>
                <p className="text-xs text-muted">
                  {r.interval_start} to {r.interval_end}
                  {r.row_count !== null && ` · ${r.row_count} rows`}
                  {r.error && ` · ${r.error}`}
                </p>
              </div>
              <div className="flex items-center gap-3">
                <Badge tone={STATUS_TONE[r.status]} data-testid="report-status">
                  {r.status}
                </Badge>
                {r.download_url && (
                  <a href={r.download_url} download className="text-sm font-medium text-brand-700 hover:underline">
                    Download
                  </a>
                )}
              </div>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-muted">Download links work for 15 minutes; open this page again for a new one.</p>
      </Card>
    </div>
  );
}
