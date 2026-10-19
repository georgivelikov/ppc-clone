import { Alert, Button, FieldError, Input, Label, formatMoney } from "@cutpay/ui";
import { useState, type FormEvent } from "react";
import { CheckoutApiError, confirmBlik, type Session } from "./api";

interface Props {
  secret: string;
  session: Session;
  onUpdate: (session: Session) => void;
}

/**
 * Pay with BLIK, the Polish bank-app method. The customer types the six digit code from their banking app; the bank
 * then asks them to confirm in the app, which the page waits for (see BlikWaiting).
 */
export function BlikPanel({ secret, session, onUpdate }: Props) {
  const [code, setCode] = useState("");
  const [email, setEmail] = useState("");
  const [problems, setProblems] = useState<{ code?: string; email?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const found: { code?: string; email?: string } = {};
    if (!/^[0-9]{6}$/.test(code)) found.code = "Enter the six digit code from your banking app";
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) found.email = "Enter a valid email";
    setProblems(found);
    if (found.code || found.email) return;
    setError(null);
    setBusy(true);
    try {
      onUpdate(await confirmBlik(secret, code, email.trim()));
    } catch (e) {
      if (e instanceof CheckoutApiError && e.code === "invalid_blik_code") {
        setProblems({ code: "That code is not valid or has expired. Generate a new one in your app." });
      } else {
        setError(e instanceof CheckoutApiError && e.status < 500 ? e.message : "We could not process your payment. Please try again.");
      }
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4" aria-label="Pay with BLIK">
      <h1 className="text-xl font-semibold">Pay with BLIK</h1>
      {error && <Alert>{error}</Alert>}
      <div>
        <Label htmlFor="blik-email">Email</Label>
        <Input
          id="blik-email"
          type="email"
          autoComplete="email"
          value={email}
          aria-invalid={!!problems.email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <FieldError>{problems.email}</FieldError>
      </div>
      <div>
        <Label htmlFor="blik-code">BLIK code</Label>
        <Input
          id="blik-code"
          inputMode="numeric"
          autoComplete="off"
          maxLength={6}
          placeholder="123456"
          value={code}
          aria-invalid={!!problems.code}
          aria-describedby="blik-hint"
          onChange={(event) => setCode(event.target.value.replace(/[^0-9]/g, "").slice(0, 6))}
        />
        <FieldError>{problems.code}</FieldError>
        <p id="blik-hint" className="mt-1 text-xs text-muted">
          Open your banking app and generate a BLIK code.
        </p>
      </div>
      <Button type="submit" className="w-full" loading={busy}>
        Pay {formatMoney(session.amount, session.currency)}
      </Button>
      <p className="text-center text-xs text-muted">Test code: 777123 pays, 000000 is declined.</p>
    </form>
  );
}
