import { Alert, formatMoney } from "@cutpay/ui";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router";
import { CheckoutApiError, getSession, type Session } from "./api";
import { BlikWaiting } from "./BlikWaiting";
import { PaymentMethods } from "./PaymentMethods";
import { Shell } from "./Shell";
import { ThreeDsChallenge } from "./ThreeDsChallenge";
import { Outcome } from "./Outcome";

function OrderSummary({ session }: { session: Session }) {
  return (
    <div>
      <p className="text-sm text-muted">{session.merchant_name ?? "Merchant"}</p>
      <p className="mt-1 text-3xl font-semibold" data-testid="amount">
        {formatMoney(session.amount, session.currency)}
      </p>
      {session.description && <p className="mt-3 text-sm text-muted">{session.description}</p>}
    </div>
  );
}

export function CheckoutPage() {
  const { secret = "" } = useParams();
  const queryClient = useQueryClient();
  const key = ["session", secret];
  const { data: session, error, isPending } = useQuery({ queryKey: key, queryFn: () => getSession(secret) });

  const update = (next: Session) => queryClient.setQueryData(key, next);

  if (isPending) {
    return (
      <Shell>
        <p className="text-sm text-muted" role="status">
          Loading payment…
        </p>
      </Shell>
    );
  }
  if (error || !session) {
    const notFound = error instanceof CheckoutApiError && error.status === 404;
    return (
      <Shell>
        <h1 className="text-xl font-semibold">{notFound ? "Payment link not found" : "Something went wrong"}</h1>
        <Alert className="mt-4">
          {notFound ? "This payment link is not valid." : "We could not load this payment. Please try again."}
        </Alert>
      </Shell>
    );
  }

  const aside = <OrderSummary session={session} />;
  switch (session.status) {
    case "requires_payment_method":
      return (
        <Shell aside={aside}>
          <PaymentMethods secret={secret} session={session} onUpdate={update} />
        </Shell>
      );
    case "requires_action":
      return (
        <Shell aside={aside}>
          {session.next_action?.type === "blik" ? (
            <BlikWaiting secret={secret} onDone={update} />
          ) : (
            <ThreeDsChallenge session={session} onDone={() => queryClient.invalidateQueries({ queryKey: key })} />
          )}
        </Shell>
      );
    default:
      return (
        <Shell aside={aside}>
          <Outcome session={session} />
        </Shell>
      );
  }
}
