import {
  ApiError,
  type Application,
  type ApplicationPerson,
  type ApplicationStep,
  type BusinessType,
  type DocumentKind,
  type PersonRole,
  type RegisteredCompany,
} from "@cutpay/api-client";
import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Circle } from "lucide-react";
import { useState, type FormEvent, type ReactNode } from "react";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong. Please try again.");

const STEPS: { key: ApplicationStep | "review"; title: string }[] = [
  { key: "business", title: "Business" },
  { key: "people", title: "People" },
  { key: "documents", title: "Documents" },
  { key: "bank", title: "Bank account" },
  { key: "profile", title: "About your sales" },
  { key: "review", title: "Review and submit" },
];

const INDUSTRIES: Record<string, string> = {
  retail: "Retail",
  hospitality: "Hospitality",
  health_beauty: "Health and beauty",
  transport: "Transport",
  charity: "Charity",
  startup: "Startup",
  gambling: "Gambling (needs a manual review)",
  crypto: "Crypto (needs a manual review)",
};

const STATUS_TEXT: Record<Application["status"], { title: string; tone: "danger" | "info"; text: string }> = {
  pending_kyc: { title: "Not submitted", tone: "info", text: "Fill in the steps below, then submit. Until you are approved, everything works in test mode." },
  in_review: { title: "In review", tone: "info", text: "We are checking your documents. This usually takes a few seconds in test mode; some applications are also read by a person." },
  needs_more_info: { title: "More information needed", tone: "danger", text: "Fix what is listed below and submit again." },
  active: { title: "Approved", tone: "info", text: "Your business is approved. Live mode is open." },
  rejected: { title: "Not approved", tone: "danger", text: "We could not approve this application." },
  suspended: { title: "Suspended", tone: "danger", text: "This account is suspended." },
};

/** The application to take live payments, one step at a time. */
export function OnboardingPage() {
  const { role } = useSession();
  const queryClient = useQueryClient();
  const app = useQuery({
    queryKey: ["application"],
    queryFn: () => api.getApplication(),
    enabled: role === "owner" || role === "admin",
    refetchInterval: (q) => (q.state.data?.status === "in_review" ? 2_000 : false),
  });
  const [step, setStep] = useState<ApplicationStep | "review">("business");

  if (role !== "owner" && role !== "admin") {
    return <Alert>Only owners and admins can work on the application.</Alert>;
  }
  if (app.isError) return <Alert>Could not load the application.</Alert>;
  const a = app.data;
  if (!a) return <p className="text-sm text-muted">Loading…</p>;

  const refresh = (updated: Application) => {
    queryClient.setQueryData(["application"], updated);
    void queryClient.invalidateQueries({ queryKey: ["merchant"] });
  };
  const editable = a.status === "pending_kyc" || a.status === "needs_more_info";
  const status = STATUS_TEXT[a.status];

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Get approved for live payments</h1>
        <p className="mt-1 text-sm text-muted">
          A few questions about your business and the people behind it. Use made-up details and sample images only: this is a study project.
        </p>
      </div>

      <Alert tone={status.tone} role="status" data-testid="application-status">
        <p className="font-medium" data-testid="application-state">{status.title}</p>
        <p>{status.text}</p>
        {a.decision_reason && <p className="mt-1" data-testid="decision-reason">{a.decision_reason}</p>}
        {a.status === "in_review" && a.manual_review && (
          <p className="mt-1">A person at Cutpay will read this application, so it takes longer.</p>
        )}
      </Alert>

      <nav aria-label="Steps" className="flex flex-wrap gap-2">
        {STEPS.map((s) => {
          const done = s.key !== "review" && !a.missing.includes(s.key);
          return (
            <button
              key={s.key}
              type="button"
              onClick={() => setStep(s.key)}
              aria-current={step === s.key ? "step" : undefined}
              className={`flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm ${step === s.key ? "border-brand-600 bg-brand-50 text-brand-700" : "border-border"}`}
            >
              {done ? <Check className="size-3.5 text-success" aria-label="done" /> : <Circle className="size-3 text-muted" aria-hidden />}
              {s.title}
            </button>
          );
        })}
      </nav>

      <fieldset disabled={!editable} className="min-w-0 space-y-6 border-0 p-0">
        {step === "business" && <BusinessStep app={a} onSaved={refresh} />}
        {step === "people" && <PeopleStep app={a} onSaved={refresh} />}
        {step === "documents" && <DocumentsStep app={a} onSaved={refresh} />}
        {step === "bank" && <BankStep app={a} onSaved={refresh} />}
        {step === "profile" && <ProfileStep app={a} onSaved={refresh} />}
        {step === "review" && <ReviewStep app={a} onSaved={refresh} editable={editable} />}
      </fieldset>
    </div>
  );
}

interface StepProps {
  app: Application;
  onSaved(app: Application): void;
}

function Section({ title, children, hint }: { title: string; children: ReactNode; hint?: string }) {
  return (
    <Card>
      <h2 className="font-semibold">{title}</h2>
      {hint && <p className="mt-1 text-sm text-muted">{hint}</p>}
      <div className="mt-4 space-y-4">{children}</div>
    </Card>
  );
}

function useSave<T>(fn: (input: T) => Promise<Application>, onSaved: (app: Application) => void) {
  const [problem, setProblem] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: fn,
    onSuccess: (updated) => {
      setProblem(null);
      onSaved(updated);
    },
    onError: (e) => setProblem(message(e)),
  });
  return { problem, setProblem, mutation };
}

function BusinessStep({ app, onSaved }: StepProps) {
  const [type, setType] = useState<BusinessType>(app.data.business_type ?? "company");
  const [industry, setIndustry] = useState(app.data.industry ?? "retail");
  const [number, setNumber] = useState(app.data.registration_number ?? "");
  const [found, setFound] = useState<RegisteredCompany | null>(app.data.company ?? null);
  const [lookupProblem, setLookupProblem] = useState<string | null>(null);
  const { problem, setProblem, mutation } = useSave(
    () => api.saveBusiness({ business_type: type, industry, registration_number: number.trim() || undefined }),
    onSaved,
  );
  const lookup = useMutation({
    mutationFn: () => api.lookupCompany(number.trim()),
    onSuccess: (c) => {
      setFound(c);
      setLookupProblem(null);
    },
    onError: (e) => {
      setFound(null);
      setLookupProblem(message(e));
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    mutation.mutate(undefined);
  };
  return (
    <Section title="Your business" hint={`The register of ${app.data.country ?? "your country"} is searched by registration number (BG: EIK, RO: CUI, PL: KRS).`}>
      <form onSubmit={submit} noValidate className="space-y-4" aria-label="Business">
        {problem && <Alert>{problem}</Alert>}
        <div role="radiogroup" aria-label="Type of business" className="flex flex-wrap gap-4 text-sm">
          {(["sole_trader", "company", "nonprofit"] as const).map((t) => (
            <label key={t} className="flex items-center gap-2">
              <input type="radio" name="business-type" checked={type === t} onChange={() => setType(t)} />
              {t === "sole_trader" ? "Sole trader" : t === "company" ? "Company" : "Nonprofit"}
            </label>
          ))}
        </div>
        <div>
          <Label htmlFor="industry">Industry</Label>
          <select id="industry" value={industry} onChange={(e) => setIndustry(e.target.value)} className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm">
            {Object.entries(INDUSTRIES).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <Label htmlFor="reg-number">Registration number{type === "sole_trader" ? " (optional)" : ""}</Label>
          <div className="flex gap-2">
            <Input id="reg-number" value={number} onChange={(e) => { setNumber(e.target.value); setFound(null); }} />
            <Button type="button" variant="secondary" loading={lookup.isPending} onClick={() => lookup.mutate()} disabled={!number.trim()}>
              Look up
            </Button>
          </div>
          {lookupProblem && <p className="mt-1 text-sm text-danger" role="alert">{lookupProblem}</p>}
        </div>
        {found && (
          <div className="rounded-md border border-border bg-canvas p-3 text-sm" data-testid="company-found">
            <p className="font-medium">{found.name}</p>
            <p className="text-muted">
              {found.address.line1}, {found.address.city} · {found.legal_form} · {found.status}
            </p>
            <p className="text-muted">Managed by {found.representatives.map((r) => r.name).join(", ")}</p>
          </div>
        )}
        <Button type="submit" loading={mutation.isPending}>
          Save business
        </Button>
        {mutation.isSuccess && <span role="status" className="ml-3 text-sm text-muted">Saved</span>}
      </form>
    </Section>
  );
}

const ROLE_LABEL: Record<PersonRole, string> = { representative: "Representative", ubo: "Owner (25% or more)", director: "Director" };

function PersonForm({ app, existing, onSaved, onDone }: StepProps & { existing?: ApplicationPerson; onDone(): void }) {
  const [role, setRole] = useState<PersonRole>(existing?.role ?? (app.persons.length === 0 ? "representative" : "ubo"));
  const [name, setName] = useState(existing?.name ?? "");
  const [dob, setDob] = useState(existing?.date_of_birth ?? "");
  const [nationality, setNationality] = useState(existing?.nationality ?? app.data.country ?? "");
  const [pct, setPct] = useState(existing?.ownership_pct?.toString() ?? "");
  const [docType, setDocType] = useState(existing?.document_type ?? "id_card");
  const [docNumber, setDocNumber] = useState(existing?.document_number ?? "");
  const { problem, setProblem, mutation } = useSave(
    () => {
      const input = {
        role,
        name,
        date_of_birth: dob,
        nationality,
        ownership_pct: role === "ubo" ? Number(pct) || null : null,
        document_type: docType,
        document_number: docNumber,
      };
      return existing ? api.updatePerson(existing.id, input) : api.addPerson(input);
    },
    (updated) => {
      onSaved(updated);
      onDone();
    },
  );
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setProblem(null);
        mutation.mutate(undefined);
      }}
      noValidate
      className="grid gap-3 rounded-md border border-border p-4 sm:grid-cols-2"
      aria-label={existing ? "Edit person" : "Add a person"}
    >
      {problem && <Alert className="sm:col-span-2">{problem}</Alert>}
      <div>
        <Label htmlFor="p-role">Role</Label>
        <select id="p-role" value={role} onChange={(e) => setRole(e.target.value as PersonRole)} className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm">
          {(Object.keys(ROLE_LABEL) as PersonRole[]).map((r) => (
            <option key={r} value={r}>
              {ROLE_LABEL[r]}
            </option>
          ))}
        </select>
      </div>
      <div>
        <Label htmlFor="p-name">Full name</Label>
        <Input id="p-name" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div>
        <Label htmlFor="p-dob">Date of birth</Label>
        <Input id="p-dob" type="date" value={dob} onChange={(e) => setDob(e.target.value)} />
      </div>
      <div>
        <Label htmlFor="p-nat">Nationality (2 letters)</Label>
        <Input id="p-nat" maxLength={2} value={nationality} onChange={(e) => setNationality(e.target.value.toUpperCase())} />
      </div>
      {role === "ubo" && (
        <div>
          <Label htmlFor="p-pct">Share owned (%)</Label>
          <Input id="p-pct" inputMode="numeric" value={pct} onChange={(e) => setPct(e.target.value)} />
        </div>
      )}
      <div>
        <Label htmlFor="p-doctype">Document</Label>
        <select id="p-doctype" value={docType} onChange={(e) => setDocType(e.target.value)} className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm">
          <option value="id_card">ID card</option>
          <option value="passport">Passport</option>
          <option value="driving_licence">Driving licence</option>
        </select>
      </div>
      <div>
        <Label htmlFor="p-docnum">Document number</Label>
        <Input id="p-docnum" value={docNumber} onChange={(e) => setDocNumber(e.target.value)} />
        <p className="mt-1 text-xs text-muted">Test rule: a number ending in 0 is rejected, in 9 needs a better photo.</p>
      </div>
      <div className="flex gap-3 sm:col-span-2">
        <Button type="submit" loading={mutation.isPending}>
          {existing ? "Save person" : "Add person"}
        </Button>
        <Button type="button" variant="secondary" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function PeopleStep({ app, onSaved }: StepProps) {
  const [adding, setAdding] = useState(app.persons.length === 0);
  const [editing, setEditing] = useState<string | null>(null);
  const remove = useMutation({ mutationFn: (id: string) => api.removePerson(id), onSuccess: onSaved });
  return (
    <Section title="People" hint="At least one representative, and for a company or nonprofit at least one owner of 25% or more.">
      {remove.isError && <Alert>{message(remove.error)}</Alert>}
      <ul className="divide-y divide-border" aria-label="People">
        {app.persons.map((p) =>
          editing === p.id ? (
            <li key={p.id} className="py-3">
              <PersonForm app={app} existing={p} onSaved={onSaved} onDone={() => setEditing(null)} />
            </li>
          ) : (
            <li key={p.id} className="flex flex-wrap items-center justify-between gap-3 py-3" data-testid={`person-${p.name}`}>
              <div>
                <p className="text-sm font-medium">
                  {p.name} <span className="text-muted">· {ROLE_LABEL[p.role]}{p.ownership_pct ? ` ${p.ownership_pct}%` : ""}</span>
                </p>
                <p className="text-xs text-muted">
                  {p.document_type.replace("_", " ")} {p.document_number}
                  {p.verification_status !== "not_started" && ` · ${p.verification_status.replaceAll("_", " ")}`}
                  {p.verification_reason ? ` (${p.verification_reason})` : ""}
                </p>
              </div>
              <div className="flex gap-2">
                <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(p.id)}>
                  Edit
                </Button>
                <Button type="button" size="sm" variant="secondary" aria-label={`Remove ${p.name}`} onClick={() => remove.mutate(p.id)}>
                  Remove
                </Button>
              </div>
            </li>
          ),
        )}
      </ul>
      {adding ? (
        <PersonForm app={app} onSaved={onSaved} onDone={() => setAdding(false)} />
      ) : (
        <Button type="button" variant="secondary" onClick={() => setAdding(true)}>
          Add a person
        </Button>
      )}
    </Section>
  );
}

function FileSlot({ app, kind, personId, label, onSaved }: StepProps & { kind: DocumentKind; personId?: string; label: string }) {
  const existing = app.documents.find((d) => d.type === kind && (d.person_id ?? undefined) === personId);
  const [problem, setProblem] = useState<string | null>(null);
  const upload = useMutation({
    mutationFn: (file: File) => api.uploadApplicationDocument(kind, file, personId),
    onSuccess: async () => onSaved(await api.getApplication()),
    onError: (e) => setProblem(message(e)),
  });
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 py-2 text-sm" data-testid={`slot-${personId ?? "business"}-${kind}`}>
      <span>
        {label} {existing ? <Badge tone="success">{existing.filename}</Badge> : <Badge>missing</Badge>}
      </span>
      <input
        type="file"
        accept="image/*,application/pdf"
        aria-label={`${label}${personId ? ` for ${app.persons.find((p) => p.id === personId)?.name}` : ""}`}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) {
            setProblem(null);
            upload.mutate(file);
          }
          e.target.value = "";
        }}
      />
      {problem && <span className="w-full text-danger" role="alert">{problem}</span>}
    </div>
  );
}

function DocumentsStep({ app, onSaved }: StepProps) {
  return (
    <Section title="Identity documents" hint="A photo of each person's ID and a selfie. Use sample images only (a PNG or JPG of anything, up to 5 MB).">
      {app.persons.length === 0 && <p className="text-sm text-muted">Add the people first.</p>}
      {app.persons.map((p) => (
        <div key={p.id} className="rounded-md border border-border px-4 py-2">
          <p className="text-sm font-medium">{p.name}</p>
          <FileSlot app={app} onSaved={onSaved} kind="id_front" personId={p.id} label="ID, front" />
          <FileSlot app={app} onSaved={onSaved} kind="id_back" personId={p.id} label="ID, back (optional)" />
          <FileSlot app={app} onSaved={onSaved} kind="selfie" personId={p.id} label="Selfie" />
        </div>
      ))}
    </Section>
  );
}

function BankStep({ app, onSaved }: StepProps) {
  const [iban, setIban] = useState(app.data.iban ?? "");
  const [holder, setHolder] = useState(app.data.account_holder ?? app.data.company?.name ?? "");
  const { problem, setProblem, mutation } = useSave(() => api.saveBank({ iban, account_holder: holder }), onSaved);
  return (
    <Section title="Bank account" hint="Where payouts go. A bank statement or a letter from the bank proves it is yours (a sample image will do).">
      <form onSubmit={(e) => { e.preventDefault(); setProblem(null); mutation.mutate(undefined); }} noValidate className="space-y-4" aria-label="Bank account">
        {problem && <Alert>{problem}</Alert>}
        <div>
          <Label htmlFor="b-iban">IBAN</Label>
          <Input id="b-iban" value={iban} onChange={(e) => setIban(e.target.value)} placeholder="BG80 BNBG 9661 1020 3456 78" />
        </div>
        <div>
          <Label htmlFor="b-holder">Name on the account</Label>
          <Input id="b-holder" value={holder} onChange={(e) => setHolder(e.target.value)} />
        </div>
        <Button type="submit" loading={mutation.isPending}>
          Save bank account
        </Button>
        {mutation.isSuccess && <span role="status" className="ml-3 text-sm text-muted">Saved</span>}
      </form>
      <FileSlot app={app} onSaved={onSaved} kind="bank_proof" label="Proof of the account" />
    </Section>
  );
}

function ProfileStep({ app, onSaved }: StepProps) {
  const [website, setWebsite] = useState(app.data.website ?? "");
  const [description, setDescription] = useState(app.data.description ?? "");
  const [volume, setVolume] = useState(app.data.expected_monthly_volume ? String(app.data.expected_monthly_volume / 100) : "");
  const { problem, setProblem, mutation } = useSave(
    () => api.saveProfile({ website, description, expected_monthly_volume: Math.round(Number(volume) * 100) }),
    onSaved,
  );
  return (
    <Section title="About your sales">
      <form onSubmit={(e) => { e.preventDefault(); setProblem(null); mutation.mutate(undefined); }} noValidate className="space-y-4" aria-label="About your sales">
        {problem && <Alert>{problem}</Alert>}
        <div>
          <Label htmlFor="s-site">Website (optional)</Label>
          <Input id="s-site" value={website} onChange={(e) => setWebsite(e.target.value)} placeholder="https://" />
        </div>
        <div>
          <Label htmlFor="s-desc">What do you sell, and to whom?</Label>
          <textarea id="s-desc" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm" />
        </div>
        <div>
          <Label htmlFor="s-vol">Expected sales per month (in euros)</Label>
          <Input id="s-vol" inputMode="decimal" value={volume} onChange={(e) => setVolume(e.target.value)} />
        </div>
        <Button type="submit" loading={mutation.isPending}>
          Save
        </Button>
        {mutation.isSuccess && <span role="status" className="ml-3 text-sm text-muted">Saved</span>}
      </form>
    </Section>
  );
}

function ReviewStep({ app, onSaved, editable }: StepProps & { editable: boolean }) {
  const [accepted, setAccepted] = useState(false);
  const [signed, setSigned] = useState("");
  const { problem, setProblem, mutation } = useSave(() => api.submitApplication({ accepted_terms: accepted, signed_name: signed }), onSaved);
  const names: Record<ApplicationStep, string> = { business: "Business", people: "People", documents: "Documents", bank: "Bank account", profile: "About your sales" };
  return (
    <Section title="Review and submit">
      {app.missing.length > 0 ? (
        <Alert tone="info" role="status">
          Still to do: {app.missing.map((m) => names[m]).join(", ")}.
        </Alert>
      ) : (
        <p className="text-sm text-muted">Everything is filled in.</p>
      )}
      <dl className="divide-y divide-border text-sm">
        <div className="flex justify-between py-2"><dt className="text-muted">Business</dt><dd>{app.data.company?.name ?? "Sole trader"}</dd></div>
        <div className="flex justify-between py-2"><dt className="text-muted">Industry</dt><dd>{INDUSTRIES[app.data.industry ?? ""] ?? "-"}</dd></div>
        <div className="flex justify-between py-2"><dt className="text-muted">People</dt><dd>{app.persons.length}</dd></div>
        <div className="flex justify-between py-2"><dt className="text-muted">IBAN</dt><dd>{app.data.iban ?? "-"}</dd></div>
      </dl>
      {editable && (
        <form onSubmit={(e) => { e.preventDefault(); setProblem(null); mutation.mutate(undefined); }} noValidate className="space-y-4" aria-label="Submit">
          {problem && <Alert>{problem}</Alert>}
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} className="mt-1" />I confirm that the answers are true and I accept the Cutpay terms of service.
          </label>
          <div>
            <Label htmlFor="sign">Type your full name to sign</Label>
            <Input id="sign" value={signed} onChange={(e) => setSigned(e.target.value)} />
          </div>
          <Button type="submit" loading={mutation.isPending} disabled={app.missing.length > 0}>
            Submit application
          </Button>
        </form>
      )}
    </Section>
  );
}
