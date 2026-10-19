import { ApiError, type FxConversion, type FxQuote } from "@cutpay/api-client";
import { Trans, useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Alert, Button, FieldError, Input, Label, toMajorString, toMinorUnits } from "@cutpay/ui";
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

/**
 * Counts down a quote's life, once a second; zero when it has passed. The server says how long the quote lasts rather than
 * when it ends, so the count does not depend on the browser's clock agreeing with the server's.
 */
function useSecondsLeft(total: number | undefined, quoteId: string | undefined): number {
  const [startedAt, setStartedAt] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (quoteId === undefined) return;
    const start = Date.now();
    setStartedAt(start);
    setNow(start);
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [quoteId]);
  return total === undefined ? 0 : Math.max(0, total - Math.floor((now - startedAt) / 1000));
}

const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

/**
 * Converts money from one balance to another. First the rate is locked in a quote that holds for 15 minutes, with the
 * result spelled out (what it is worth, the markup, what arrives); then the merchant confirms, and that exact rate is used.
 */
export function ConvertDialog({ from, available, preferred, onClose }: Props) {
  const { t } = useTranslation();
  const { money: formatMoney, number, locale } = useLocaleFormat();
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
  const secondsLeft = useSecondsLeft(quote?.expires_in, quote?.id);
  const expired = quote !== null && secondsLeft === 0;

  async function getQuote(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const minor = toMinorUnits(amount, from);
    if (minor === null) {
      setAmountError(t("fx.invalidAmount"));
      return;
    }
    if (minor > available) {
      setAmountError(t("fx.onlyAvailable", { amount: formatMoney(available, from) }));
      return;
    }
    setAmountError(undefined);
    setBusy(true);
    try {
      setQuote(await api.createFxQuote({ from_currency: from, to_currency: to, amount: minor }, !testMode));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("fx.failed"));
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
      if (e instanceof ApiError && e.code === "quote_expired") setQuote((q) => (q ? { ...q, expires_in: 0 } : q));
      setError(e instanceof ApiError ? e.message : t("fx.failed"));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <Dialog title={t("fx.converted")} onClose={onClose}>
        <p className="text-sm" data-testid="conversion-result">
          <Trans
            i18nKey="fx.became"
            values={{ from: formatMoney(done.from_amount, done.from_currency), to: formatMoney(done.to_amount, done.to_currency) }}
            components={{ strong: <strong /> }}
          />
        </p>
        <p className="mt-2 text-sm text-muted">
          {t("fx.resultNote", {
            mid: formatMoney(done.mid_amount, done.to_currency),
            markup: formatMoney(done.markup_amount, done.to_currency),
            percent: number(done.markup_bps / 100),
          })}
        </p>
        <div className="mt-4 flex justify-end">
          <Button onClick={onClose}>{t("fx.done")}</Button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title={t("fx.convertTitle", { currency: from })} onClose={onClose}>
      {error && <Alert className="mb-3">{error}</Alert>}
      <form onSubmit={getQuote} noValidate className="space-y-4" aria-label={t("fx.formLabel")}>
        <div className="grid grid-cols-[1fr_6rem] gap-3">
          <div>
            <Label htmlFor="convert-amount">{t("fx.amount")}</Label>
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
            <Label htmlFor="convert-to">{t("fx.into")}</Label>
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
        <p className="text-xs text-muted">{t("fx.available", { amount: formatMoney(available, from) })}</p>

        {quote && (
          <div className="rounded-lg border border-border p-3 text-sm" data-testid="quote">
            <p>
              <Trans
                i18nKey="fx.receive"
                values={{ amount: formatMoney(quote.to_amount, quote.to_currency) }}
                components={{ strong: <strong data-testid="quote-receive" /> }}
              />
            </p>
            <dl className="mt-2 space-y-1 text-xs text-muted">
              <div className="flex justify-between">
                <dt>{t("fx.marketRate")}</dt>
                <dd>
                  1 {quote.from_currency} = {formatRate(quote.rate, locale)} {quote.to_currency}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt>{t("fx.worth")}</dt>
                <dd>{formatMoney(quote.mid_amount, quote.to_currency)}</dd>
              </div>
              <div className="flex justify-between">
                <dt>{t("fx.markup", { percent: number(quote.markup_bps / 100) })}</dt>
                <dd>−{formatMoney(quote.markup_amount, quote.to_currency)}</dd>
              </div>
            </dl>
            <p className={`mt-2 text-xs ${expired ? "text-danger" : "text-muted"}`} role="timer" data-testid="quote-timer">
              {expired ? t("fx.expired") : t("fx.held", { time: clock(secondsLeft) })}
            </p>
          </div>
        )}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("fx.cancel")}
          </Button>
          {quote && !expired ? (
            <Button type="button" loading={busy} onClick={() => void convert()}>
              {t("fx.convert")}
            </Button>
          ) : (
            <Button type="submit" loading={busy}>
              {expired ? t("fx.getNewQuote") : t("fx.getQuote")}
            </Button>
          )}
        </div>
      </form>
    </Dialog>
  );
}
