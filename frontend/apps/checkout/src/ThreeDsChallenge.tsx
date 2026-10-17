import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
import { ShieldCheck } from "lucide-react";
import { useState } from "react";
import { CheckoutApiError, completeThreeDs, type Session } from "./api";

/**
 * The fake bank's 3-D Secure page. A real one is hosted by the card issuer; here the code is always 1234
 * and "Fail authentication" simulates a customer who cannot prove who they are.
 */
export function ThreeDsChallenge({ session, onDone }: { session: Session; onDone: () => void }) {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const challengeId = session.next_action?.challenge_id;

  async function answer(submitted: string) {
    if (!challengeId) return;
    setBusy(true);
    setError(null);
    try {
      await completeThreeDs(challengeId, submitted);
    } catch (e) {
      // The bank rejected the code; payments was told and the payment is now failed. Show that outcome.
      if (!(e instanceof CheckoutApiError) || e.status >= 500) {
        setError("We could not reach your bank. Please try again.");
        setBusy(false);
        return;
      }
    }
    onDone();
  }

  return (
    <div>
      <div className="flex items-center gap-2 text-brand-700">
        <ShieldCheck className="size-5" aria-hidden />
        <h1 className="text-xl font-semibold text-ink">Confirm with your bank</h1>
      </div>
      <p className="mt-2 text-sm text-muted">
        Your bank needs to verify this payment. Enter the one-time code <strong>1234</strong>.
      </p>
      {error && <Alert className="mt-4">{error}</Alert>}
      <form
        className="mt-4 space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void answer(code);
        }}
      >
        <div>
          <Label htmlFor="otp">One-time code</Label>
          <Input
            id="otp"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={8}
            value={code}
            onChange={(event) => setCode(event.target.value)}
          />
          <FieldError />
        </div>
        <div className="flex gap-3">
          <Button type="submit" className="flex-1" loading={busy} disabled={code.length === 0}>
            Approve
          </Button>
          <Button type="button" variant="secondary" disabled={busy} onClick={() => void answer("0000")}>
            Fail authentication
          </Button>
        </div>
      </form>
    </div>
  );
}
