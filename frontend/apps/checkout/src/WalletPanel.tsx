import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { Smartphone, X } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { authenticateWallet, CheckoutApiError, confirm, getWalletCards, type Session, type WalletType } from "./api";

const WALLETS: { type: WalletType; name: string }[] = [
  { type: "apple_pay", name: "Apple Pay" },
  { type: "google_pay", name: "Google Pay" },
];

interface Props {
  secret: string;
  session: Session;
  onUpdate: (session: Session) => void;
}

/**
 * Pay with a wallet. Real wallets need certificates and a registered merchant, so this one is pretend: a sheet in the
 * page, generic on purpose, where the customer picks a stored test card and "confirms on their device". The wallet
 * hands back a token, like a real one, and the payment then goes through without a 3-D Secure challenge.
 */
export function WalletPanel({ secret, session, onUpdate }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<WalletType | null>(null);
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("wallet.title")}</h1>
      <p className="text-sm text-muted">{t("wallet.intro")}</p>
      <div className="grid gap-3">
        {WALLETS.map((wallet) => (
          <Button key={wallet.type} type="button" variant="secondary" className="w-full" onClick={() => setOpen(wallet.type)}>
            <Smartphone className="mr-2 size-4" aria-hidden />
            {t("wallet.payWith", { wallet: wallet.name })}
          </Button>
        ))}
      </div>
      {open && (
        <WalletSheet
          wallet={WALLETS.find((wallet) => wallet.type === open)!}
          secret={secret}
          session={session}
          onClose={() => setOpen(null)}
          onUpdate={(next) => {
            setOpen(null);
            onUpdate(next);
          }}
        />
      )}
    </div>
  );
}

function WalletSheet({
  wallet,
  secret,
  session,
  onClose,
  onUpdate,
}: Props & { wallet: { type: WalletType; name: string }; onClose: () => void }) {
  const { t } = useTranslation();
  const { money } = useLocaleFormat();
  const cards = useQuery({ queryKey: ["wallet-cards"], queryFn: getWalletCards, staleTime: Infinity, retry: false });
  const [cardId, setCardId] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [emailError, setEmailError] = useState<string | undefined>();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const first = useRef<HTMLInputElement>(null);
  const selected = cardId ?? cards.data?.[0]?.id ?? null;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => {
    first.current?.focus();
  }, [cards.data]);

  async function pay(event: FormEvent) {
    event.preventDefault();
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) {
      setEmailError(t("wallet.emailInvalid"));
      return;
    }
    setEmailError(undefined);
    setError(null);
    setBusy(true);
    try {
      // The customer "confirms on their device"; the wallet answers with a token for the chosen card.
      const { token } = await authenticateWallet(wallet.type, selected!);
      onUpdate(await confirm(secret, token, email.trim()));
    } catch (e) {
      setError(e instanceof CheckoutApiError && e.status < 500 ? e.message : t("card.failed"));
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-4 sm:items-center">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t("wallet.sheetLabel", { wallet: wallet.name })}
        className="max-h-full w-full max-w-sm overflow-y-auto rounded-2xl bg-surface p-5 shadow-xl"
      >
        <div className="flex items-start justify-between gap-2">
          <div>
            <h2 className="text-lg font-semibold">{wallet.name}</h2>
            <p className="text-sm text-muted">
              {session.merchant_name ?? t("page.merchant")} · {money(session.amount, session.currency)}
            </p>
          </div>
          <button type="button" aria-label={t("wallet.close")} onClick={onClose} className="rounded-md p-1 text-muted hover:bg-canvas">
            <X className="size-5" aria-hidden />
          </button>
        </div>
        <p className="mt-2 rounded-md bg-canvas px-3 py-2 text-xs text-muted">
          {t("wallet.disclaimer")}
        </p>
        {error && <Alert className="mt-3">{error}</Alert>}
        <form onSubmit={pay} noValidate className="mt-4 space-y-4" aria-label={t("wallet.payWith", { wallet: wallet.name })}>
          <fieldset>
            <legend className="text-sm font-medium">{t("wallet.card")}</legend>
            {cards.isPending && <p className="mt-2 text-sm text-muted">{t("wallet.loadingCards")}</p>}
            {cards.isError && <Alert className="mt-2">{t("wallet.unavailable")}</Alert>}
            <div className="mt-2 space-y-2">
              {cards.data?.map((card, index) => (
                <label key={card.id} className="flex cursor-pointer items-center gap-3 rounded-lg border border-border p-3 text-sm">
                  <input
                    ref={index === 0 ? first : undefined}
                    type="radio"
                    name="wallet-card"
                    value={card.id}
                    checked={selected === card.id}
                    onChange={() => setCardId(card.id)}
                  />
                  <span className="flex-1">
                    {card.label}
                    <span className="block text-xs text-muted">
                      {card.brand} ···· {card.last4}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <div>
            <Label htmlFor="wallet-email">{t("wallet.email")}</Label>
            <Input
              id="wallet-email"
              type="email"
              autoComplete="email"
              value={email}
              aria-invalid={!!emailError}
              onChange={(event) => setEmail(event.target.value)}
            />
            <FieldError>{emailError}</FieldError>
          </div>
          <Button type="submit" className="w-full" loading={busy} disabled={!selected}>
            {t("wallet.confirm")}
          </Button>
        </form>
      </div>
    </div>
  );
}
