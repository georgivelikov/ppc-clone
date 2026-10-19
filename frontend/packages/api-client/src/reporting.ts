// Reports and the numbers on the home page (backend/services/reporting).

export type ReportFormat = "csv" | "xlsx";
export type ReportRunStatus = "queued" | "running" | "succeeded" | "failed";

export interface ReportType {
  id: string;
  title: string;
  about: string;
  columns: { key: string; label: string }[];
}

export interface ReportRun {
  object: "report_run";
  id: string;
  type: string;
  format: ReportFormat;
  interval_start: string;
  interval_end: string;
  columns: string[] | null;
  status: ReportRunStatus;
  error: string | null;
  row_count: number | null;
  created: string;
  completed_at: string | null;
  /** A signed link, only on a finished run; it stops working after `download_expires_in` seconds. */
  download_url: string | null;
  download_expires_in: number | null;
}

export interface CreateReportRunInput {
  type: string;
  format?: ReportFormat;
  interval_start: string;
  interval_end: string;
  columns?: string[];
}

export interface OverviewMoney {
  currency: string;
  gross: number;
  fees: number;
  refunds: number;
  disputes: number;
  net: number;
}

export interface Overview {
  days: number;
  money: OverviewMoney[];
  succeeded: number;
  failed: number;
  /** Null until there is a payment that succeeded or failed. */
  success_rate: number | null;
  decline_reasons: { key: string; count: number }[];
  method_mix: { key: string; count: number }[];
  funnel: { opened: number; attempted: number; paid: number };
}
