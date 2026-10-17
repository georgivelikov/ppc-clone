import { Alert, Badge, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { getOrder } from "../api";

/** Where the customer lands after paying. It shows "paid" only once the webhook has told the shop so. */
export function ThanksPage() {
  const [params] = useSearchParams();
  const orderId = params.get("order") ?? "";
  const { data: order, error } = useQuery({
    queryKey: ["order", orderId, "status"],
    queryFn: () => getOrder(orderId),
    enabled: orderId !== "",
    // Keep asking while the webhook has not arrived yet.
    refetchInterval: (query) => (query.state.data?.status === "pending" ? 1000 : false),
  });

  if (!orderId || error) return <Alert>We could not find that order.</Alert>;
  if (!order) return <p className="text-sm text-muted">Loading…</p>;

  return (
    <section aria-labelledby="thanks-heading" className="mx-auto max-w-lg" data-order-status={order.status}>
      <Card>
        {order.status === "paid" && (
          <>
            <h1 id="thanks-heading" className="text-2xl font-semibold">
              Thank you! Your order is paid
            </h1>
            <p className="mt-2 text-sm text-muted">
              We received {formatMoney(order.amount, order.currency)} for order {order.id}.
            </p>
          </>
        )}
        {order.status === "pending" && (
          <>
            <h1 id="thanks-heading" className="text-2xl font-semibold">
              Waiting for confirmation…
            </h1>
            <p className="mt-2 text-sm text-muted" role="status">
              We are waiting for Cutpay to confirm the payment for order {order.id}.
            </p>
          </>
        )}
        {order.status === "failed" && (
          <>
            <h1 id="thanks-heading" className="text-2xl font-semibold">
              Payment did not go through
            </h1>
            <p className="mt-2 text-sm text-muted">Order {order.id} was not paid. You have not been charged.</p>
          </>
        )}
        <p className="mt-4">
          <Badge tone={order.status === "paid" ? "success" : order.status === "failed" ? "danger" : "warning"}>{order.status}</Badge>
        </p>
        <Link to="/orders" className="mt-4 inline-block text-sm text-brand-600 hover:underline">
          View all orders
        </Link>
      </Card>
    </section>
  );
}
