import { ApiError, type FxConversion, type FxQuote } from "@cutpay/api-client";
import { Alert, Button, FieldError, Input, Label, formatMoney, toMajorString, toMinorUnits } from "@cutpay/ui";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../api";
import { Dialog } from "../../components/Dialog";
import { useTestMode } from "../../layout/testMode";
import { CURRENCIES, formatRate } from "./fx";

interface Props {
  /** The currency to convert from, and how much of it is available. */
  from: string;
  available: number;
  /** The currency offered first. */
  preferred: string;
  onClose: () => void;
}

/** Counts down to a moment, once a second; zero when it has passed. */
function useSecondsLeft(until: string | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!until) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [until]);
  return until ? Math.max(0, Math.ceil((new Date(until).getTime() - now) / 1000)) : 0;
}

const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

/**
 * Converts money from one balance to another. First the rate is locked in a quote that holds for 15 minutes, with the
 * result spelled out (what it is worth, the markup, what arrives); then the merchant confirms, and that exact rate is used.
 */
export function ConvertDialog({ from, available, preferred, onClose }: Props) {
  const { testMode } = useTestMode();
  const queryClient = useQueryClient();
  const targets = CURRENCIES.filter((code) => code !== from);
  const [to, setTo] = useState(targets.includes(preferred) ? preferred : targets[0]!);
  const [amount, setAmount] = useState(toMajorString(available, from));
  const [amountError, setAmountError] = useState<string | undefined>();
  const [quote, setQuote] = useState<FxQuote | null>(null);
  const [done, setDone] = useState<FxConversion | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const secondsLeft = useSecondsLeft(quote?.expires_at);
  const expired = quote !== null && secondsLeft === 0;

  async function getQuote(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const minor = toMinorUnits(amount, from);
    if (minor === null) {
      setAmountError("Enter a valid amount, e.g. 12.50");
      return;
    }
    if (minor > available) {
      setAmountError(`Only ${formatMoney(available, from)} is available`);
      return;
    }
    setAmountError(undefined);
    setBusy(true);
    try {
      setQuote(await api.createFxQuote({ from_currency: from, to_currency: to, amount: minor }, !testMode));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  async function convert() {
    if (!quote) return;
    setError(null);
    setBusy(true);
    try {
      setDone(
        await api.convertBalance(
          { from_currency: quote.from_currency, to_currency: quote.to_currency, amount: quote.from_amount, quote_id: quote.id },
          !testMode,
        ),
      );
      void queryClient.invalidateQueries({ queryKey: ["balance"] });
      void queryClient.invalidateQueries({ queryKey: ["balance-transactions"] });
      void queryClient.invalidateQueries({ queryKey: ["fx-report"] });
      void queryClient.invalidateQueries({ queryKey: ["fx-conversions"] });
    } catch (e) {
      if (e instanceof ApiError && e.code === "quote_expired") setQuote((q) => (q ? { ...q, expires_at: new Date(0).toISOString() } : q));
      setError(e instanceof ApiError ? e.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <Dialog title="Converted" onClose={onClose}>
        <p className="text-sm" data-testid="conversion-result">
          {formatMoney(done.from_amount, done.from_currency)} became <strong>{formatMoney(done.to_amount, done.to_currency)}</strong>.
        </p>
        <p className="mt-2 text-sm text-muted">
          The market value was {formatMoney(done.mid_amount, done.to_currency)}; the markup of {formatMoney(done.markup_amount, done.to_currency)} (
          {done.markup_bps / 100}%) is kept by Cutpay.
        </p>
        <div className="mt-4 flex justify-end">
          <Button onClick={onClose}>Done</Button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title={`Convert ${from}`} onClose={onClose}>
      {error && <Alert className="mb-3">{error}</Alert>}
      <form onSubmit={getQuote} noValidate className="space-y-4" aria-label="Convert money">
        <div className="grid grid-cols-[1fr_6rem] gap-3">
          <div>
            <Label htmlFor="convert-amount">Amount to convert</Label>
            <Input
              id="convert-amount"
              inputMode="decimal"
              value={amount}
              aria-invalid={!!amountError}
              disabled={quote !== null && !expired}
              onChange={(event) => setAmount(event.target.value)}
            />
            <FieldError>{amountError}</FieldError>
          </div>
          <div>
            <Label htmlFor="convert-to">Into</Label>
            <select
              id="convert-to"
              value={to}
              disabled={quote !== null && !expired}
              onChange={(event) => setTo(event.target.value)}
              className="h-10 w-full rounded-lg border border-border bg-surface px-2 text-sm"
            >
              {targets.map((code) => (
                <option key={code}>{code}</option>
              ))}
            </select>
          </div>
        </div>
        <p className="text-xs text-muted">Available: {formatMoney(available, from)}</p>

        {quote && (
          <div className="rounded-lg border border-border p-3 text-sm" data-testid="quote">
            <p>
              You receive <strong data-testid="quote-receive">{formatMoney(quote.to_amount, quote.to_currency)}</strong>
            </p>
            <dl className="mt-2 space-y-1 text-xs text-muted">
              <div className="flex justify-between">
                <dt>Market rate</dt>
                <dd>
                  1 {quote.from_currency} = {formatRate(quote.rate)} {quote.to_currency}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt>Worth at the market rate</dt>
                <dd>{formatMoney(quote.mid_amount, quote.to_currency)}</dd>
              </div>
              <div className="flex justify-between">
                <dt>Markup ({quote.markup_bps / 100}%)</dt>
                <dd>−{formatMoney(quote.markup_amount, quote.to_currency)}</dd>
              </div>
            </dl>
            <p className={`mt-2 text-xs ${expired ? "text-danger" : "text-muted"}`} role="timer" data-testid="quote-timer">
              {expired ? "This quote has expired." : `This rate is held for ${clock(secondsLeft)}.`}
            </p>
          </div>
        )}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          {quote && !expired ? (
            <Button type="button" loading={busy} onClick={() => void convert()}>
              Convert
            </Button>
          ) : (
            <Button type="submit" loading={busy}>
              {expired ? "Get a new quote" : "Get a quote"}
            </Button>
          )}
        </div>
      </form>
    </Dialog>
  );
}
