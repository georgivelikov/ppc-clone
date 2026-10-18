import { Alert, Button, FieldError, Input, Label, formatMoney } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Hourglass } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { CheckoutApiError, type Session } from "./api";
import { costText, fieldsFor, planText } from "./bnpl";
import {
  applyBnpl,
  applyBnplBest,
  cancelBnplApplication,
  getBnplApplication,
  type BnplApplicant,
  type BnplApplicationView,
  type BnplField,
  type BnplOffer,
  type BnplOffers,
} from "./bnplApi";

const BEST = "best";

interface Tried {
  id: string;
  provider_name: string;
  status: string;
}

const LABEL: Record<BnplField, string> = {
  email: "Email",
  birthdate: "Date of birth",
  national_id: "National ID number",
  monthly_income: "Monthly income (after tax)",
};

const FAILED_STATES = ["declined", "failed", "expired", "canceled"];

/** Checks what the customer typed; the provider decides what it makes of it. */
export function validate(fields: BnplField[], values: Record<BnplField, string>, today = new Date()): Partial<Record<BnplField, string>> {
  const problems: Partial<Record<BnplField, string>> = {};
  if (fields.includes("email") && !/^\S+@\S+\.\S+$/.test(values.email.trim())) problems.email = "Enter a valid email";
  if (fields.includes("birthdate")) {
    const born = new Date(values.birthdate);
    if (!values.birthdate || Number.isNaN(born.getTime()) || born > today) problems.birthdate = "Enter your date of birth";
  }
  if (fields.includes("national_id") && !/^[0-9A-Za-z]{6,16}$/.test(values.national_id.trim())) {
    problems.national_id = "Enter your ID number (6 to 16 letters or digits)";
  }
  if (fields.includes("monthly_income")) {
    const income = Number(values.monthly_income);
    if (!values.monthly_income.trim() || Number.isNaN(income) || income <= 0) problems.monthly_income = "Enter your monthly income";
  }
  return problems;
}

function applicantFrom(fields: BnplField[], values: Record<BnplField, string>): BnplApplicant {
  const applicant: BnplApplicant = { email: values.email.trim() };
  if (fields.includes("birthdate")) applicant.birthdate = values.birthdate;
  if (fields.includes("national_id")) applicant.national_id = values.national_id.trim();
  if (fields.includes("monthly_income")) applicant.monthly_income = Math.round(Number(values.monthly_income) * 100);
  return applicant;
}

interface Props {
  secret: string;
  session: Session;
  offers: BnplOffers;
}

/** The "Pay in instalments" side of the checkout: choose a plan, tell the provider what it needs, and wait for its answer. */
export function BnplPanel({ secret, session, offers }: Props) {
  const queryClient = useQueryClient();
  const recommended = offers.offers.find((o) => o.plan === offers.recommended?.plan) ?? offers.offers[0]!;
  const [choice, setChoice] = useState<string>(offers.routing_mode !== "customer_choice" ? BEST : recommended.plan);
  const [values, setValues] = useState<Record<BnplField, string>>({ email: "", birthdate: "", national_id: "", monthly_income: "" });
  const [problems, setProblems] = useState<Partial<Record<BnplField, string>>>({});
  const [fallback, setFallback] = useState(true);
  const [formError, setFormError] = useState<string | null>(null);
  const [application, setApplication] = useState<BnplApplicationView | null>(null);
  const [tried, setTried] = useState<Tried[]>([]);

  const chosen: BnplOffer = choice === BEST ? recommended : (offers.offers.find((o) => o.plan === choice) ?? recommended);
  const fields = fieldsFor([chosen]);

  const apply = useMutation({
    mutationFn: async (): Promise<{ application: BnplApplicationView; tried: Tried[] }> => {
      const applicant = applicantFrom(fields, values);
      if (choice === BEST) {
        const result = await applyBnplBest(secret, applicant, fallback);
        return { application: result.application, tried: result.attempts };
      }
      return { application: await applyBnpl(secret, chosen.provider, chosen.plan, applicant), tried: [] };
    },
    onSuccess: (result) => {
      setApplication(result.application);
      setTried(result.tried);
    },
    onError: (error) => {
      setFormError(
        error instanceof CheckoutApiError && error.status < 500 ? error.message : "We could not send your application. Please try again.",
      );
    },
  });

  // The provider may take a while to decide: keep asking until it has.
  const polling = useQuery({
    queryKey: ["bnpl-application", application?.id],
    queryFn: () => getBnplApplication(secret, application!.id),
    enabled: application !== null && application.awaiting_decision,
    refetchInterval: 1500,
  });
  // What the provider said replaces what is shown, but only if it is for the application on screen and it moves on:
  // the application stays the one source of truth, so a stale answer cannot bring a canceled one back.
  useEffect(() => {
    const answer = polling.data;
    if (answer && answer.id === application?.id && answer.status !== application.status) {
      setApplication((shown) => (shown && shown.id === answer.id && shown.awaiting_decision ? answer : shown));
    }
  }, [polling.data, application?.id, application?.status]);
  const current = application;

  const cancel = useMutation({
    mutationFn: () => cancelBnplApplication(secret, application!.id),
    onSuccess: (result) => setApplication(result),
  });

  // Once approved, the payment is completed by the platform a moment later: look at the session until it has changed.
  const approved = current?.status === "approved" || current?.status === "settled";
  useEffect(() => {
    if (!approved) return;
    const timer = setInterval(() => queryClient.invalidateQueries({ queryKey: ["session", secret] }), 1000);
    return () => clearInterval(timer);
  }, [approved, queryClient, secret]);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate(fields, values);
    setProblems(found);
    if (Object.keys(found).length === 0) apply.mutate();
  };

  const startOver = () => {
    setApplication(null);
    setTried([]);
    setFormError(null);
  };

  if (current) {
    if (approved) {
      return (
        <div className="text-center" role="status">
          <CheckCircle2 className="mx-auto size-12 text-success" aria-hidden />
          <h1 className="mt-4 text-xl font-semibold">Approved by {current.provider_name}</h1>
          <p className="mt-2 text-sm text-muted">Finishing your payment…</p>
        </div>
      );
    }
    if (current.awaiting_decision) {
      return (
        <div className="text-center" role="status">
          <Hourglass className="mx-auto size-12 text-brand-600" aria-hidden />
          <h1 className="mt-4 text-xl font-semibold">Waiting for {current.provider_name}</h1>
          <p className="mt-2 text-sm text-muted">{current.message}</p>
          <p className="mt-1 text-xs text-muted">Please keep this page open.</p>
          <Button type="button" variant="secondary" className="mt-6" loading={cancel.isPending} onClick={() => cancel.mutate()}>
            Cancel the application
          </Button>
        </div>
      );
    }
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold">{FAILED_STATES.includes(current.status) ? "That did not work out" : "Pay in instalments"}</h1>
        <Alert>{current.message}</Alert>
        {tried.length > 1 && (
          <p className="text-sm text-muted" data-testid="tried">
            We asked {tried.map((t) => `${t.provider_name} (${t.status})`).join(", then ")}.
          </p>
        )}
        <Button type="button" className="w-full" onClick={startOver}>
          Choose another option
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4" aria-label="Pay in instalments">
      <h1 className="text-xl font-semibold">Pay in instalments</h1>
      {formError && <Alert>{formError}</Alert>}
      {offers.unavailable.length > 0 && (
        <p className="text-xs text-muted">
          {offers.unavailable.map((u) => u.name).join(", ")} can not be reached right now.
        </p>
      )}
      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-medium">Choose a plan</legend>
        {offers.routing_mode !== "customer_choice" && (
          <label className={optionClass(choice === BEST)}>
            <input type="radio" name="plan" value={BEST} checked={choice === BEST} onChange={() => setChoice(BEST)} className="mt-1" />
            <span>
              <span className="block font-medium">Best option</span>
              <span className="block text-xs text-muted">We pick the provider most likely to say yes, at a good price.</span>
            </span>
          </label>
        )}
        {offers.offers.map((offer) => (
          <label key={offer.plan} className={optionClass(choice === offer.plan)}>
            <input type="radio" name="plan" value={offer.plan} checked={choice === offer.plan} onChange={() => setChoice(offer.plan)} className="mt-1" />
            <span>
              <span className="block font-medium">
                {offer.provider_name}: {planText(offer, session.currency)}
              </span>
              <span className="block text-xs text-muted">{costText(offer, session.currency)}</span>
            </span>
          </label>
        ))}
      </fieldset>

      {fields.map((field) => (
        <div key={field}>
          <Label htmlFor={`bnpl-${field}`}>{LABEL[field]}</Label>
          <Input
            id={`bnpl-${field}`}
            type={field === "email" ? "email" : field === "birthdate" ? "date" : field === "monthly_income" ? "number" : "text"}
            inputMode={field === "monthly_income" ? "decimal" : undefined}
            autoComplete={field === "email" ? "email" : field === "birthdate" ? "bday" : "off"}
            aria-invalid={!!problems[field]}
            value={values[field]}
            onChange={(event) => setValues({ ...values, [field]: event.target.value })}
          />
          <FieldError>{problems[field]}</FieldError>
        </div>
      ))}

      {choice === BEST && (
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" checked={fallback} onChange={(event) => setFallback(event.target.checked)} className="mt-1" />
          <span>If the first provider cannot help, try the next best ones</span>
        </label>
      )}

      <Button type="submit" className="w-full" loading={apply.isPending}>
        Apply for {formatMoney(session.amount, session.currency)} in instalments
      </Button>
      <p className="text-center text-xs text-muted">
        The provider decides. {session.merchant_name ?? "The merchant"} is paid when it approves you.
      </p>
    </form>
  );
}

function optionClass(selected: boolean): string {
  return `flex cursor-pointer items-start gap-3 rounded-lg border p-3 text-sm ${selected ? "border-brand-600 bg-brand-50" : "border-border"}`;
}
