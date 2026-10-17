import { ApiError, type CheckoutSessionCreated } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, formatMoney, Input, Label, toMinorUnits } from "@cutpay/ui";
import { CheckCircle2, XCircle } from "lucide-react";
import { useState, type FormEvent } from "react";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { TerminalTabs } from "../../layout/TerminalTabs";
import { useTestMode } from "../../layout/testMode";
import { useBlobUrl } from "../../lib/blob";
import { usePaymentStatus } from "./usePaymentStatus";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];

interface Charge {
  session: CheckoutSessionCreated;
  amount: number;
  currency: string;
  description: string;
}

/**
 * Full-screen code for the customer to scan, e.g. on a tablet at the counter. The screen follows the payment live
 * and turns into a big tick the moment it is paid, so the merchant can see it from across the room.
 */
function QrScreen({ charge, onDone }: { charge: Charge; onDone: () => void }) {
  const { merchant } = useSession();
  const { status, failureCode } = usePaymentStatus(charge.session.payment_id);
  const { url, error } = useBlobUrl(() => api.checkoutQr(charge.session.url), [charge.session.url]);
  const paid = status === "succeeded";
  const failed = status === "failed" || status === "canceled";

  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center gap-6 bg-white p-6 text-center" role="dialog" aria-label="Charge with QR">
      <p className="text-lg text-muted">{merchant.display_name}</p>
      <p className="text-6xl font-semibold tabular-nums" data-testid="qr-amount">
        {formatMoney(charge.amount, charge.currency)}
      </p>
      {charge.description && <p className="text-lg text-muted">{charge.description}</p>}

      {paid ? (
        <div role="status" className="flex flex-col items-center gap-2 text-success">
          <CheckCircle2 className="size-48" aria-hidden />
          <p className="text-3xl font-semibold">Paid</p>
        </div>
      ) : failed ? (
        <div role="status" className="flex flex-col items-center gap-2 text-danger">
          <XCircle className="size-48" aria-hidden />
          <p className="text-3xl font-semibold">{status === "canceled" ? "Canceled" : "Payment failed"}</p>
          {failureCode && <p className="text-sm text-muted">{failureCode.replaceAll("_", " ")}</p>}
        </div>
      ) : (
        <>
          <div className="grid size-80 place-items-center rounded-2xl border border-border p-3">
            {url ? (
              <img src={url} alt="QR code to pay" className="size-full" />
            ) : (
              <span className="text-sm text-muted">{error ? "Could not draw the code" : "Drawing…"}</span>
            )}
          </div>
          <p className="text-lg" role="status">
            Scan with your phone to pay
          </p>
          <p className="text-sm text-muted">Waiting for payment…</p>
        </>
      )}

      <div className="flex gap-3">
        {!paid && !failed && (
          <a href={charge.session.url} target="_blank" rel="noreferrer" className="inline-flex h-10 items-center rounded-lg border border-border px-4 text-sm font-medium hover:bg-canvas">
            Open payment page
          </a>
        )}
        <Button
          variant={paid || failed ? "primary" : "secondary"}
          onClick={() => {
            // An unpaid charge is canceled, so a code that is still on someone's phone cannot be paid later.
            if (!paid && !failed) api.cancelPayment(charge.session.payment_id).catch(() => undefined);
            onDone();
          }}
        >
          {paid || failed ? "New charge" : "Cancel"}
        </Button>
      </div>
    </div>
  );
}

export function ChargeQrPage() {
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState(merchant.default_currency);
  const [description, setDescription] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [charge, setCharge] = useState<Charge | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const minor = toMinorUnits(amount, currency);
    if (minor === null) {
      setProblem("Enter an amount, like 12.50");
      return;
    }
    setProblem(null);
    setSaving(true);
    try {
      const session = await api.createCheckoutSession(
        { amount: minor, currency, description: description.trim() || undefined, merchant_name: merchant.display_name },
        !testMode,
      );
      setCharge({ session, amount: minor, currency, description: description.trim() });
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
      <Card>
        <h2 className="mb-1 font-semibold">Charge with QR</h2>
        <p className="mb-4 text-sm text-muted">Enter the amount, then show the code to your customer. It turns green when they have paid.</p>
        <form onSubmit={submit} noValidate className="space-y-4" aria-label="Charge with QR">
          <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
            <div>
              <Label htmlFor="qr-amount">Amount</Label>
              <Input id="qr-amount" inputMode="decimal" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="25.00" aria-invalid={Boolean(problem)} />
              <FieldError>{problem}</FieldError>
            </div>
            <div>
              <Label htmlFor="qr-currency">Currency</Label>
              <select id="qr-currency" value={currency} onChange={(e) => setCurrency(e.target.value)} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm">
                {CURRENCIES.map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            </div>
          </div>
          <div>
            <Label htmlFor="qr-description">What is it for? (optional)</Label>
            <Input id="qr-description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Table 4" />
          </div>
          {formError && <Alert>{formError}</Alert>}
          <Button type="submit" loading={saving}>
            Show QR code
          </Button>
        </form>
      </Card>
      {charge && (
        <QrScreen
          charge={charge}
          onDone={() => {
            setCharge(null);
            setAmount("");
          }}
        />
      )}
    </div>
  );
}
