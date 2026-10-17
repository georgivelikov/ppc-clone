import { ApiError, type Payment } from "@cutpay/api-client";
import { Elements, type CardElement } from "@cutpay/checkout-js";
import { Alert, Button, Card, FieldError, formatMoney, Input, Label, toMinorUnits } from "@cutpay/ui";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { TerminalTabs } from "../../layout/TerminalTabs";
import { useTestMode } from "../../layout/testMode";
import { CHECKOUT_URL } from "../../lib/blob";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];

/**
 * The card input is Cutpay's own iframe, exactly as on a customer's checkout: the card number is typed into our
 * origin and never reaches this dashboard's code. Mounting it needs no publishable key here because the frame
 * only talks to the payment it is given the secret of.
 */
function CardStep({ payment, onStartOver }: { payment: Payment; onStartOver: () => void }) {
  const navigate = useNavigate();
  const mount = useRef<HTMLDivElement>(null);
  const card = useRef<CardElement | null>(null);
  const [ready, setReady] = useState(false);
  const [complete, setComplete] = useState(false);
  const [cardError, setCardError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [paying, setPaying] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!mount.current) return;
    const element = new Elements(payment.client_secret, "", CHECKOUT_URL).create("card");
    element.on("ready", () => setReady(true));
    element.on("change", (state) => {
      setComplete(state.complete);
      setCardError(state.error);
    });
    element.mount(mount.current);
    card.current = element;
    return () => {
      element.unmount();
      card.current = null;
    };
  }, [payment.client_secret]);

  async function charge() {
    if (!card.current) return;
    setPaying(true);
    setProblem(null);
    const result = await card.current.confirm(payment.client_secret, payment.customer_email ?? undefined);
    if (!result.ok) {
      setProblem(result.error.message);
      setPaying(false);
      return;
    }
    if (result.session.status === "succeeded") {
      navigate(`/terminal/receipt/${payment.id}`);
    } else {
      // A declined payment is over; a new charge is needed to try another card.
      setProblem(result.session.failure_message ?? "The card was declined.");
      setFailed(true);
      setPaying(false);
    }
  }

  return (
    <Card className="space-y-4" aria-label="Card details">
      <div className="flex items-baseline justify-between">
        <h2 className="font-semibold">Card details</h2>
        <span className="text-xl font-semibold tabular-nums" data-testid="charge-amount">
          {formatMoney(payment.amount, payment.currency)}
        </span>
      </div>
      <p className="text-xs text-muted">Card not present (phone or mail order). The bank will not ask for 3-D Secure.</p>
      <div ref={mount} data-testid="card-mount" />
      {cardError && (
        <p role="alert" className="text-sm text-danger">
          {cardError}
        </p>
      )}
      {problem && <Alert>{problem}</Alert>}
      <div className="flex gap-2">
        {!failed && (
          <Button onClick={charge} loading={paying} disabled={!ready || !complete || paying}>
            Charge {formatMoney(payment.amount, payment.currency)}
          </Button>
        )}
        <Button variant="secondary" onClick={onStartOver} disabled={paying}>
          {failed ? "Start a new charge" : "Cancel"}
        </Button>
      </div>
      <p className="text-xs text-muted">Test card: 4242 4242 4242 4242, any future date, any CVC.</p>
    </Card>
  );
}

export function TerminalPage() {
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState(merchant.default_currency);
  const [description, setDescription] = useState("");
  const [email, setEmail] = useState("");
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [payment, setPayment] = useState<Payment | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const found: Record<string, string> = {};
    const minor = toMinorUnits(amount, currency);
    if (minor === null) found.amount = "Enter an amount, like 12.50";
    if (email.trim() && !/^\S+@\S+\.\S+$/.test(email.trim())) found.email = "Enter a valid email";
    setProblems(found);
    if (minor === null || Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      setPayment(
        await api.createTerminalCharge(
          {
            amount: minor,
            currency,
            description: description.trim() || undefined,
            customer_email: email.trim() || undefined,
            merchant_name: merchant.display_name,
          },
          !testMode,
        ),
      );
    } catch (error) {
      setFormError(error instanceof ApiError && error.status < 500 ? error.message : "Could not start the charge. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Terminal</h1>
        <p className="mt-1 text-sm text-muted">Take a payment in person or over the phone.</p>
      </div>
      <TerminalTabs />
      {payment ? (
        <CardStep
          payment={payment}
          onStartOver={() => {
            // An unpaid charge is canceled so it cannot be paid later by anyone holding its link.
            api.cancelPayment(payment.id).catch(() => undefined);
            setPayment(null);
            setAmount("");
          }}
        />
      ) : (
        <Card>
          <h2 className="mb-1 font-semibold">Virtual POS</h2>
          <p className="mb-4 text-sm text-muted">Key in the card details your customer gives you, like a card machine.</p>
          <form onSubmit={submit} noValidate className="space-y-4" aria-label="New charge">
            <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
              <div>
                <Label htmlFor="amount">Amount</Label>
                <Input id="amount" inputMode="decimal" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="25.00" aria-invalid={Boolean(problems.amount)} />
                <FieldError>{problems.amount}</FieldError>
              </div>
              <div>
                <Label htmlFor="currency">Currency</Label>
                <select id="currency" value={currency} onChange={(e) => setCurrency(e.target.value)} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm">
                  {CURRENCIES.map((c) => (
                    <option key={c}>{c}</option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <Label htmlFor="description">Description (optional)</Label>
              <Input id="description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Phone order #12" />
            </div>
            <div>
              <Label htmlFor="email">Customer email (optional)</Label>
              <Input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} aria-invalid={Boolean(problems.email)} />
              <FieldError>{problems.email}</FieldError>
            </div>
            {formError && <Alert>{formError}</Alert>}
            <Button type="submit" loading={saving}>
              Continue
            </Button>
          </form>
        </Card>
      )}
    </div>
  );
}
