import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
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
  const { t } = useTranslation();
  const { money } = useLocaleFormat();
  const [code, setCode] = useState("");
  const [email, setEmail] = useState("");
  const [problems, setProblems] = useState<{ code?: string; email?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const found: { code?: string; email?: string } = {};
    if (!/^[0-9]{6}$/.test(code)) found.code = t("blik.codeRequired");
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) found.email = t("blik.emailInvalid");
    setProblems(found);
    if (found.code || found.email) return;
    setError(null);
    setBusy(true);
    try {
      onUpdate(await confirmBlik(secret, code, email.trim()));
    } catch (e) {
      if (e instanceof CheckoutApiError && e.code === "invalid_blik_code") {
        setProblems({ code: t("blik.codeInvalid") });
      } else {
        setError(e instanceof CheckoutApiError && e.status < 500 ? e.message : t("card.failed"));
      }
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4" aria-label={t("blik.title")}>
      <h1 className="text-xl font-semibold">{t("blik.title")}</h1>
      {error && <Alert>{error}</Alert>}
      <div>
        <Label htmlFor="blik-email">{t("blik.email")}</Label>
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
        <Label htmlFor="blik-code">{t("blik.code")}</Label>
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
          {t("blik.codeHint")}
        </p>
      </div>
      <Button type="submit" className="w-full" loading={busy}>
        {t("blik.pay", { amount: money(session.amount, session.currency) })}
      </Button>
      <p className="text-center text-xs text-muted">{t("blik.testCode")}</p>
    </form>
  );
}
