import { Badge, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { getOrders } from "../api";

export function OrdersPage() {
  const { data: orders, isPending } = useQuery({ queryKey: ["orders"], queryFn: getOrders, refetchInterval: 2000 });

  return (
    <section aria-labelledby="orders-heading">
      <h1 id="orders-heading" className="mb-6 text-2xl font-semibold">
        Orders
      </h1>
      {isPending ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : !orders || orders.length === 0 ? (
        <p className="text-sm text-muted">No orders yet.</p>
      ) : (
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-muted">
            <tr>
              <th className="py-2 font-medium">Order</th>
              <th className="py-2 font-medium">Items</th>
              <th className="py-2 font-medium">Total</th>
              <th className="py-2 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <tr key={order.id} className="border-b border-border">
                <td className="py-2 font-mono text-xs">{order.id}</td>
                <td className="py-2">{order.lines.map((line) => `${line.quantity}× ${line.name}`).join(", ")}</td>
                <td className="py-2">{formatMoney(order.amount, order.currency)}</td>
                <td className="py-2">
                  <Badge tone={order.status === "paid" ? "success" : order.status === "failed" ? "danger" : "warning"}>
                    {order.status}
                  </Badge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
