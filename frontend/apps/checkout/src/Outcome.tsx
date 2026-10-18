import { formatMoney } from "@cutpay/ui";
import { CheckCircle2, XCircle } from "lucide-react";
import type { Session } from "./api";

/** The end states of a payment: paid, failed, canceled. */
export function Outcome({ session }: { session: Session }) {
  const held = session.status === "requires_capture";
  const paid = held || ["succeeded", "partially_refunded", "refunded"].includes(session.status);
  const processing = session.status === "processing";

  let title = "Payment failed";
  let message = session.failure_message ?? "Your payment could not be completed.";
  if (held) {
    title = "Payment authorised";
    message = `${formatMoney(session.amount, session.currency)} is reserved on your card. ${session.merchant_name ?? "The merchant"} will take the payment later; you may see it as pending.`;
  } else if (paid) {
    title = "Payment successful";
    message = `${formatMoney(session.amount, session.currency)} was paid to ${session.merchant_name ?? "the merchant"}.`;
  } else if (processing) {
    title = "Processing your payment";
    message = "This can take a few seconds. Please do not close this page.";
  } else if (session.status === "canceled") {
    title = "Payment canceled";
    message = "This payment was canceled by the merchant. You have not been charged.";
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
      {session.status === "failed" && (
        <p className="mt-2 text-xs text-muted">You have not been charged. Ask the merchant for a new payment link to try again.</p>
      )}
      {session.return_url && (
        <a
          href={session.return_url}
          className="mt-6 inline-block text-sm font-medium text-brand-600 hover:underline"
        >
          Return to {session.merchant_name ?? "merchant"}
        </a>
      )}
    </div>
  );
}
