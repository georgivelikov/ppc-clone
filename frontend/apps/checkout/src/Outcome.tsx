import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { CheckCircle2, XCircle } from "lucide-react";
import type { Session } from "./api";

/** The end states of a payment: paid, failed, canceled. */
export function Outcome({ session }: { session: Session }) {
  const { t } = useTranslation();
  const { money } = useLocaleFormat();
  const held = session.status === "requires_capture";
  const paid = held || ["succeeded", "partially_refunded", "refunded"].includes(session.status);
  const processing = session.status === "processing";
  const amount = money(session.amount, session.currency);
  const merchant = session.merchant_name ?? t("outcome.merchantLower");

  let title = t("outcome.failed.title");
  let message = session.failure_message ?? t("outcome.failed.text");
  if (held) {
    title = t("outcome.held.title");
    message = t("outcome.held.text", { amount, merchant });
  } else if (paid) {
    title = t("outcome.paid.title");
    message = t("outcome.paid.text", { amount, merchant });
  } else if (processing) {
    title = t("outcome.processing.title");
    message = t("outcome.processing.text");
  } else if (session.status === "canceled") {
    title = t("outcome.canceled.title");
    message = t("outcome.canceled.text");
  }

  return (
    <div className="text-center" role="status">
      {paid ? (
        <CheckCircle2 className="mx-auto size-12 text-success" aria-hidden />
      ) : (
        <XCircle className="mx-auto size-12 text-danger" aria-hidden />
      )}
      <h1 className="mt-4 text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-sm text-muted">{message}</p>
      {session.status === "failed" && <p className="mt-2 text-xs text-muted">{t("outcome.failed.note")}</p>}
      {session.return_url && (
        <a href={session.return_url} className="mt-6 inline-block text-sm font-medium text-brand-600 hover:underline">
          {t("outcome.return", { merchant: session.merchant_name ?? t("outcome.merchantReturn") })}
        </a>
      )}
    </div>
  );
}
