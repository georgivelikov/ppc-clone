import { detectLocale, setLocale, storedLocale, useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Alert } from "@cutpay/ui";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useParams, useSearchParams } from "react-router";
import { CheckoutApiError, getSession, type Session } from "./api";
import { BlikWaiting } from "./BlikWaiting";
import { PaymentMethods } from "./PaymentMethods";
import { Shell } from "./Shell";
import { ThreeDsChallenge } from "./ThreeDsChallenge";
import { Outcome } from "./Outcome";

function OrderSummary({ session }: { session: Session }) {
  const { t } = useTranslation();
  const format = useLocaleFormat();
  const leva = format.bgn(session.amount, session.currency);
  return (
    <div>
      <p className="text-sm text-muted">{session.merchant_name ?? t("page.merchant")}</p>
      <p className="mt-1 text-3xl font-semibold" data-testid="amount">
        {format.money(session.amount, session.currency)}
      </p>
      {leva && (
        <p className="mt-1 text-sm text-muted" data-testid="amount-bgn">
          ≈ {leva}
        </p>
      )}
      {session.description && <p className="mt-3 text-sm text-muted">{session.description}</p>}
    </div>
  );
}

export function CheckoutPage() {
  const { secret = "" } = useParams();
  const { t } = useTranslation();
  const [search] = useSearchParams();
  const queryClient = useQueryClient();
  const key = ["session", secret];
  const { data: session, error, isPending } = useQuery({ queryKey: key, queryFn: () => getSession(secret) });

  // A language in the link, or one the customer picked before, wins; otherwise the one the merchant set for this payment.
  const explicit = search.get("locale") ?? storedLocale();
  const sessionLocale = session?.locale ?? null;
  useEffect(() => {
    if (!explicit && sessionLocale) void setLocale(detectLocale({ session: sessionLocale }), false);
  }, [explicit, sessionLocale]);

  const update = (next: Session) => queryClient.setQueryData(key, next);

  if (isPending) {
    return (
      <Shell languages>
        <p className="text-sm text-muted" role="status">
          {t("page.loading")}
        </p>
      </Shell>
    );
  }
  if (error || !session) {
    const notFound = error instanceof CheckoutApiError && error.status === 404;
    return (
      <Shell languages>
        <h1 className="text-xl font-semibold">{notFound ? t("page.notFound.title") : t("page.error.title")}</h1>
        <Alert className="mt-4">{notFound ? t("page.notFound.text") : t("page.error.text")}</Alert>
      </Shell>
    );
  }

  const aside = <OrderSummary session={session} />;
  switch (session.status) {
    case "requires_payment_method":
      return (
        <Shell languages aside={aside}>
          <PaymentMethods secret={secret} session={session} onUpdate={update} />
        </Shell>
      );
    case "requires_action":
      return (
        <Shell languages aside={aside}>
          {session.next_action?.type === "blik" ? (
            <BlikWaiting secret={secret} onDone={update} />
          ) : (
            <ThreeDsChallenge session={session} onDone={() => queryClient.invalidateQueries({ queryKey: key })} />
          )}
        </Shell>
      );
    default:
      return (
        <Shell languages aside={aside}>
          <Outcome session={session} />
        </Shell>
      );
  }
}
