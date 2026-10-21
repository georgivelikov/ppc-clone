// The back-office talks to the gateway with a staff token. It is kept for the tab only (sessionStorage): staff sessions are
// short, there is no refresh, and closing the tab signs out.

const KEY = "cutpay.staff.token";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

let token: string | null = (() => {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
})();
let onExpired: (() => void) | undefined;

export function setToken(value: string | null) {
  token = value;
  try {
    if (value) sessionStorage.setItem(KEY, value);
    else sessionStorage.removeItem(KEY);
  } catch {
    // storage can be blocked; the session then lasts until reload
  }
}

export const hasToken = () => token !== null;
export const onSessionExpired = (handler: (() => void) | undefined) => {
  onExpired = handler;
};

async function send(method: string, path: string, body?: unknown, auth = true): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (auth && token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 && auth) {
    setToken(null);
    onExpired?.();
  }
  if (!response.ok) {
    let code = "error";
    let message = "Something went wrong.";
    try {
      const payload = (await response.json()) as { error?: { code?: string; message?: string } };
      code = payload.error?.code ?? code;
      message = payload.error?.message ?? message;
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, code, message);
  }
  return response;
}

async function call<T>(method: string, path: string, body?: unknown, auth = true): Promise<T> {
  const response = await send(method, path, body, auth);
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export interface Session {
  access_token: string;
  id: string;
  email: string;
  full_name: string;
  role: "admin" | "reviewer";
}

export interface MerchantRow {
  id: string;
  display_name: string;
  legal_name: string;
  country: string;
  status: string;
  owner_email: string | null;
  created: string;
}

export interface Flag {
  flag: string;
  note: string | null;
  set_by: string;
  set_at: string;
}

export interface MerchantDetail {
  merchant: MerchantRow;
  flags: Flag[];
  payouts_frozen: boolean;
  application: { status: string; risk_score: number | string; manual_review: boolean; people: number };
  activity: { id: number; actor: string; action: string; detail: string; created_at: string }[];
}

export interface QueueRow {
  merchant_id: string;
  merchant_name: string;
  status: string;
  risk_score: number | null;
  manual_review: boolean;
  submitted_at: string;
}

export interface Person {
  id: string;
  role: string;
  name: string;
  date_of_birth: string;
  nationality: string;
  ownership_pct: number | null;
  document_type: string;
  document_number: string;
  verification_status: string;
  verification_reason: string | null;
  screening: string | null;
}

export interface ApplicationDocument {
  id: string;
  person_id: string | null;
  type: string;
  filename: string;
  size: number;
}

export interface Application {
  merchant_id: string;
  status: string;
  data: Record<string, unknown> & { company?: { name: string; legal_form: string; status: string; address: { line1: string; city: string } } };
  persons: Person[];
  documents: ApplicationDocument[];
  risk_score: number | null;
  risk_reasons: string[];
  manual_review: boolean;
  submitted_at: string | null;
  decided_by: string | null;
  decision_reason: string | null;
}

export interface Adjustment {
  id: string;
  merchant_id: string;
  currency: string;
  amount: number;
  reason: string;
  status: "pending" | "approved" | "rejected";
  requested_by: string;
  decided_by: string | null;
  decision_note: string | null;
  requested_at: string;
}

export interface StaffDispute {
  id: string;
  merchant_id: string;
  payment_id: string;
  amount: number;
  currency: string;
  fee: number;
  status: string;
  evidence_due_by: string;
  opened: string;
}

export interface AuditRow {
  id: number;
  merchant_id: string;
  actor: string;
  action: string;
  detail: string;
  created_at: string;
}

export interface ReconRun {
  id: string;
  scope_date: string | null;
  status: "running" | "clean" | "mismatched" | "failed";
  payments_checked: number;
  refunds_checked: number;
  acquirer_lines: number;
  mismatch_count: number;
  error: string | null;
  started_at: string;
}

export interface ReconMismatch {
  kind: string;
  payment_id: string;
  currency: string | null;
  payments_amount: number | null;
  ledger_amount: number | null;
  acquirer_amount: number | null;
  detail: string;
}

export interface DeadLetter {
  topic: string;
  partition: number;
  offset: number;
  original_topic: string | null;
  key: string | null;
  event_type: string | null;
  error: string | null;
  at: string;
  payload: string;
}

const list = <T>(path: string) => call<{ data: T[] }>("GET", path).then((r) => r.data);

export const api = {
  login: (email: string, password: string) => call<Session>("POST", "/auth/staff/login", { email, password }, false),
  searchMerchants: (q: string, status?: string) =>
    list<MerchantRow>(`/v1/staff/merchants?q=${encodeURIComponent(q)}${status ? `&status=${status}` : ""}`),
  merchant: (id: string) => call<MerchantDetail>("GET", `/v1/staff/merchants/${encodeURIComponent(id)}`),
  setFlag: (id: string, flag: string, note: string) => call<Flag[]>("PUT", `/v1/staff/merchants/${encodeURIComponent(id)}/flags/${flag}`, { note }),
  clearFlag: (id: string, flag: string) => call<Flag[]>("DELETE", `/v1/staff/merchants/${encodeURIComponent(id)}/flags/${flag}`),
  freeze: (id: string, note: string) => call<MerchantDetail>("POST", `/v1/staff/merchants/${encodeURIComponent(id)}/payouts/freeze`, { note }),
  unfreeze: (id: string) => call<MerchantDetail>("POST", `/v1/staff/merchants/${encodeURIComponent(id)}/payouts/unfreeze`),
  queue: (status = "in_review") => list<QueueRow>(`/v1/staff/applications?status=${status}`),
  application: (id: string) => call<Application>("GET", `/v1/staff/applications/${encodeURIComponent(id)}`),
  decide: (id: string, decision: string, reason: string) =>
    call<Application>("POST", `/v1/staff/applications/${encodeURIComponent(id)}/decision`, { decision, reason }),
  document: async (merchantId: string, documentId: string) =>
    (await send("GET", `/v1/staff/applications/${encodeURIComponent(merchantId)}/documents/${encodeURIComponent(documentId)}`)).blob(),
  adjustments: () => list<Adjustment>("/v1/staff/adjustments"),
  requestAdjustment: (input: { merchant_id: string; currency: string; amount: number; reason: string }) =>
    call<Adjustment>("POST", "/v1/staff/adjustments", input),
  approveAdjustment: (id: string, note: string) => call<Adjustment>("POST", `/v1/staff/adjustments/${id}/approve`, { note }),
  rejectAdjustment: (id: string, note: string) => call<Adjustment>("POST", `/v1/staff/adjustments/${id}/reject`, { note }),
  disputes: () => list<StaffDispute>("/v1/staff/disputes"),
  audit: () => list<AuditRow>("/v1/staff/audit?limit=100"),
  reconciliationRuns: () => list<ReconRun>("/v1/staff/reconciliation/runs"),
  reconciliationMismatches: (id: string) => list<ReconMismatch>(`/v1/staff/reconciliation/runs/${encodeURIComponent(id)}/mismatches`),
  runReconciliation: () => call<ReconRun>("POST", "/v1/staff/reconciliation/runs"),
  deadLetters: () => list<DeadLetter>("/v1/staff/dead_letters"),
  redrive: (d: DeadLetter) => call<DeadLetter>("POST", "/v1/staff/dead_letters/redrive", { topic: d.topic, partition: d.partition, offset: d.offset }),
  discard: (d: DeadLetter) => call<{ status: string }>("POST", "/v1/staff/dead_letters/discard", { topic: d.topic, partition: d.partition, offset: d.offset }),
};
