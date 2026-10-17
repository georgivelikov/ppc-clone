import { Alert, Button, Card, formatMoney, Input, Label } from "@cutpay/ui";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { createOrder, ShopApiError } from "../api";
import { useCart } from "../cart";

export function CartPage() {
  const cart = useCart();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");

  const checkout = useMutation({
    mutationFn: (mode: "embedded" | "hosted") =>
      createOrder({
        items: cart.lines.map((line) => ({ productId: line.product.id, quantity: line.quantity })),
        mode,
        email: email || undefined,
      }),
    onSuccess: (order) => {
      cart.clear();
      if (order.mode === "hosted" && order.url) {
        // Hosted: the customer pays on Cutpay's page and comes back to /thanks.
        window.location.assign(order.url);
      } else {
        navigate(`/pay/${order.id}`);
      }
    },
  });

  if (cart.lines.length === 0) {
    return (
      <section>
        <h1 className="mb-4 text-2xl font-semibold">Your cart</h1>
        <p className="text-sm text-muted">
          Your cart is empty. <Link to="/" className="text-brand-600 hover:underline">Browse products</Link>.
        </p>
      </section>
    );
  }

  const failure = checkout.error instanceof ShopApiError ? checkout.error : null;
  const busy = checkout.isPending;

  return (
    <section aria-labelledby="cart-heading">
      <h1 id="cart-heading" className="mb-6 text-2xl font-semibold">
        Your cart
      </h1>
      <Card>
        <ul className="divide-y divide-border">
          {cart.lines.map((line) => (
            <li key={line.product.id} className="flex items-center justify-between gap-4 py-3">
              <div>
                <p className="font-medium">{line.product.name}</p>
                <p className="text-sm text-muted">{formatMoney(line.product.unitAmount, line.product.currency)} each</p>
              </div>
              <div className="flex items-center gap-3">
                <Button
                  variant="secondary"
                  size="sm"
                  aria-label={`Remove one ${line.product.name}`}
                  onClick={() => cart.setQuantity(line.product.id, line.quantity - 1)}
                >
                  −
                </Button>
                <span aria-label={`${line.product.name} quantity`} className="w-6 text-center">
                  {line.quantity}
                </span>
                <Button
                  variant="secondary"
                  size="sm"
                  aria-label={`Add one ${line.product.name}`}
                  onClick={() => cart.setQuantity(line.product.id, line.quantity + 1)}
                >
                  +
                </Button>
                <span className="w-20 text-right font-medium">
                  {formatMoney(line.product.unitAmount * line.quantity, line.product.currency)}
                </span>
              </div>
            </li>
          ))}
        </ul>
        <p className="mt-4 flex justify-between border-t border-border pt-4 text-lg font-semibold">
          <span>Total</span>
          <span data-testid="cart-total">{formatMoney(cart.total, "EUR")}</span>
        </p>
      </Card>

      <Card className="mt-6">
        <Label htmlFor="email">Email for your receipt</Label>
        <Input id="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        {failure && <Alert className="mt-4">{failure.message}</Alert>}
        <div className="mt-4 flex flex-wrap gap-3">
          <Button onClick={() => checkout.mutate("embedded")} loading={busy && checkout.variables === "embedded"} disabled={busy}>
            Pay on this site
          </Button>
          <Button variant="secondary" onClick={() => checkout.mutate("hosted")} loading={busy && checkout.variables === "hosted"} disabled={busy}>
            Pay on Cutpay&apos;s page
          </Button>
        </div>
        <p className="mt-3 text-xs text-muted">
          The first shows the card input inside this page (served by Cutpay, so the shop never sees the card). The second
          redirects to Cutpay&apos;s hosted checkout.
        </p>
      </Card>
    </section>
  );
}
